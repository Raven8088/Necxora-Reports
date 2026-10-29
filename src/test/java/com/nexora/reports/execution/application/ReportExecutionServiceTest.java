package com.nexora.reports.execution.application;

import com.nexora.reports.audit.domain.ExecutionAuditRecord;
import com.nexora.reports.audit.domain.ExecutionAuditRecord.Outcome;
import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.AuditPort;
import com.nexora.reports.execution.application.port.PlanSource;
import com.nexora.reports.execution.application.port.ReadOnlyExecutor;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionWarning;
import com.nexora.reports.execution.domain.QueryResult;
import com.nexora.reports.execution.domain.ReportResult;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.plan.domain.DataSourceDescriptor;
import com.nexora.reports.plan.domain.PlanNotExecutableException;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.PlanValidity.Status;
import com.nexora.reports.plan.domain.StoredPlan;
import com.nexora.reports.support.PlanFixtures;
import com.nexora.reports.translation.domain.SqlQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExecutionServiceTest {

    private static final UUID TENANT = PlanFixtures.TENANT;
    private static final UUID SOURCE = PlanFixtures.DATA_SOURCE;
    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(5),
            Duration.ofSeconds(1), Duration.ofSeconds(10), 50, Duration.ofSeconds(1));

    private final Requester human = new Requester("analyst", Requester.PrincipalType.HUMAN, TENANT);
    private FakePlanSource semantic;
    private FakeExecutor executor;
    private List<ExecutionAuditRecord> audit;
    private Optional<DataSourceCredentials> credentials;
    private boolean auditFails;
    private ReportExecutionService service;
    private StoredPlan plan;

    @BeforeEach
    void setUp() {
        plan = PlanFixtures.read(PlanFixtures.SINGLE_OBJECT);
        semantic = new FakePlanSource();
        semantic.source = new DataSourceDescriptor(SOURCE, "postgresql", "ACTIVE");
        semantic.plan = plan;
        semantic.validities.add(current());
        executor = new FakeExecutor();
        audit = new ArrayList<>();
        credentials = Optional.of(new DataSourceCredentials(TENANT, SOURCE, "localhost", 5432, "erp", "reader",
                "secret", "disable", 2));
        AuditPort port = r -> {
            if (auditFails) {
                throw new IllegalStateException("audit down");
            }
            audit.add(r);
        };
        service = new ReportExecutionService(semantic, (t, d) -> credentials, executor, port, LIMITS,
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC), UUID::randomUUID);
    }

    @Test
    void executesTranslatesAndAuditsWithoutBusinessData() {
        executor.result = new QueryResult(List.of(List.of("2026-01-01", "revisar", "200.500", 1L)), false);
        ReportResult result = service.execute(human, SOURCE, plan.id(), null);

        assertThat(result.validity()).isEqualTo(Status.CURRENT);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.maxRows()).isEqualTo(100);
        assertThat(result.planFingerprint()).isEqualTo(plan.fingerprint());
        assertThat(executor.query.sql()).startsWith("SELECT");
        assertThat(semantic.validityCalls).as("validity checked before and after the query").isEqualTo(2);
        assertThat(semantic.tenants).containsOnly(TENANT);

        ExecutionAuditRecord record = audit.get(0);
        assertThat(record.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(record.tenantId()).isEqualTo(TENANT);
        assertThat(record.requesterSubject()).isEqualTo("analyst");
        assertThat(record.planFingerprint()).isEqualTo(plan.fingerprint());
        assertThat(record.rowCount()).isEqualTo(1);
        assertThat(record.toString()).doesNotContain("revisar", "200.500", "secret", "SELECT");
    }

    @Test
    void staleIsRefusedAndAuditedAsRejected() {
        semantic.validities.clear();
        semantic.validities.add(new PlanValidity(plan.id(), Status.STALE,
                List.of(new PlanValidity.Issue("DEFINITION_CHANGED", true, "DEFINITION", UUID.randomUUID(), "m"))));
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.PLAN_STALE);
        assertThat(executor.query).as("nothing executed").isNull();
        assertThat(audit.get(0).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(audit.get(0).errorCode()).isEqualTo("PLAN_STALE");
        assertThat(audit.get(0).validityStatus()).isNull();
    }

    @Test
    void currentWithNonBlockingChangesIsExecutedWithAWarning() {
        semantic.validities.clear();
        PlanValidity changed = new PlanValidity(plan.id(), Status.CURRENT_WITH_CHANGES, List.of(
                new PlanValidity.Issue("PHYSICAL_OBJECT_CHANGED", false, "PHYSICAL_OBJECT", UUID.randomUUID(), "m")));
        semantic.validities.add(changed);
        semantic.validities.add(changed);
        ReportResult result = service.execute(human, SOURCE, plan.id(), null);
        assertThat(result.validity()).isEqualTo(Status.CURRENT_WITH_CHANGES);
        assertThat(result.warnings()).extracting(ExecutionWarning::code)
                .containsExactly(ExecutionWarning.Code.PLAN_CURRENT_WITH_CHANGES);
        assertThat(audit.get(0).validityStatus()).isEqualTo("CURRENT_WITH_CHANGES");
    }

    @Test
    void aPlanThatBecomesStaleDuringTheQueryIsDiscarded() {
        semantic.validities.add(new PlanValidity(plan.id(), Status.STALE, List.of(
                new PlanValidity.Issue("RELATIONSHIP_NOT_USABLE", true, "RELATIONSHIP", UUID.randomUUID(), "m"))));
        executor.result = new QueryResult(List.of(List.of("2026-01-01", "revisar", "200.500", 1L)), false);
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.PLAN_CHANGED_DURING_EXECUTION);
        assertThat(executor.query).isNotNull();
        assertThat(audit.get(0).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(audit.get(0).rowCount()).isNull();
    }

    @Test
    void truncatedResultsCarryAWarning() {
        executor.result = new QueryResult(List.of(List.of("a"), List.of("b")), true);
        ReportResult result = service.execute(human, SOURCE, plan.id(), 2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.warnings()).extracting(ExecutionWarning::code)
                .containsExactly(ExecutionWarning.Code.RESULT_TRUNCATED);
        assertThat(executor.query.maxRows()).isEqualTo(2);
    }

    @Test
    void aiPrincipalsCannotExecute() {
        Requester ai = new Requester("agent", Requester.PrincipalType.AI, TENANT);
        assertCode(() -> service.execute(ai, SOURCE, plan.id(), null), ErrorCode.REQUESTER_NOT_ALLOWED);
        assertThat(semantic.tenants).isEmpty();
    }

    @Test
    void aSourceWithoutCredentialsForTheTenantIsRefusedBeforeCallingSemantic() {
        credentials = Optional.empty();
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.DATA_SOURCE_NOT_CONFIGURED);
        assertThat(semantic.tenants).isEmpty();
        credentials = Optional.of(new DataSourceCredentials(UUID.randomUUID(), SOURCE, "localhost", 5432, "erp",
                "reader", "secret", "disable", 2));
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.DATA_SOURCE_NOT_CONFIGURED);
    }

    @Test
    void aPlanOfAnotherTenantOrSourceIsAContractViolation() {
        ObjectNode foreign = PlanFixtures.mutate(PlanFixtures.SINGLE_OBJECT,
                e -> PlanFixtures.plan(e).put("tenantId", UUID.randomUUID().toString()));
        semantic.plan = PlanFixtures.read(foreign);
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.SEMANTIC_CONTRACT_VIOLATION);

        semantic.plan = plan;
        semantic.source = new DataSourceDescriptor(UUID.randomUUID(), "postgresql", "ACTIVE");
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.SEMANTIC_CONTRACT_VIOLATION);
        assertThat(executor.query).isNull();
    }

    @Test
    void archivedOrNonPostgresqlSourcesAreNotExecutable() {
        semantic.source = new DataSourceDescriptor(SOURCE, "postgresql", "ARCHIVED");
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.DATA_SOURCE_NOT_EXECUTABLE);
        semantic.source = new DataSourceDescriptor(SOURCE, "mysql", "ACTIVE");
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.DATA_SOURCE_NOT_EXECUTABLE);
    }

    @Test
    void aPlanTheValidatorRejectsIsAuditedAndNeverExecuted() {
        semantic.plan = PlanFixtures.read(PlanFixtures.mutate(PlanFixtures.LEFT_JOIN,
                e -> PlanFixtures.element(e, "joins", 0).put("multiplicationRisk", "POSSIBLE")));
        UUID id = semantic.plan.id();
        assertThatThrownBy(() -> service.execute(human, SOURCE, id, null))
                .isInstanceOf(PlanNotExecutableException.class);
        assertThat(executor.query).isNull();
        assertThat(audit.get(0).errorCode()).isEqualTo("PLAN_NOT_EXECUTABLE");
    }

    @Test
    void withoutAnAuditRecordNoResultIsReturned() {
        auditFails = true;
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.AUDIT_UNAVAILABLE);
    }

    @Test
    void infrastructureFailuresAreAuditedAsFailed() {
        executor.failure = new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, "timeout");
        assertCode(() -> service.execute(human, SOURCE, plan.id(), null), ErrorCode.EXECUTION_TIMEOUT);
        assertThat(audit.get(0).outcome()).isEqualTo(Outcome.FAILED);
        assertThat(audit.get(0).validityStatus()).isEqualTo("CURRENT");
    }

    // --- fakes ---

    private PlanValidity current() {
        return new PlanValidity(plan.id(), Status.CURRENT, List.of());
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private static final class FakePlanSource implements PlanSource {
        DataSourceDescriptor source;
        StoredPlan plan;
        final Deque<PlanValidity> validities = new ArrayDeque<>();
        final List<UUID> tenants = new ArrayList<>();
        int validityCalls;
        private PlanValidity last;

        @Override
        public DataSourceDescriptor dataSource(UUID tenantId, UUID dataSourceId) {
            tenants.add(tenantId);
            return source;
        }

        @Override
        public StoredPlan plan(UUID tenantId, UUID dataSourceId, UUID planId) {
            tenants.add(tenantId);
            return plan;
        }

        @Override
        public PlanValidity validity(UUID tenantId, UUID dataSourceId, UUID planId) {
            tenants.add(tenantId);
            validityCalls++;
            if (!validities.isEmpty()) {
                last = validities.poll();
            }
            return last;
        }
    }

    private static final class FakeExecutor implements ReadOnlyExecutor {
        SqlQuery query;
        QueryResult result = new QueryResult(List.of(), false);
        RuntimeException failure;

        @Override
        public QueryResult execute(DataSourceCredentials credentials, SqlQuery query, ExecutionLimits limits) {
            this.query = query;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
