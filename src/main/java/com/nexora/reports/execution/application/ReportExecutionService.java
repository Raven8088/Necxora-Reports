package com.nexora.reports.execution.application;

import com.nexora.reports.audit.domain.ExecutionAuditRecord;
import com.nexora.reports.audit.domain.ExecutionAuditRecord.Outcome;
import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.AuditPort;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.PlanSource;
import com.nexora.reports.execution.application.port.ReadOnlyExecutor;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionWarning;
import com.nexora.reports.execution.domain.QueryResult;
import com.nexora.reports.execution.domain.ReportResult;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.ValidityPolicy;
import com.nexora.reports.plan.domain.DataSourceDescriptor;
import com.nexora.reports.plan.domain.PlanNotExecutableException;
import com.nexora.reports.plan.domain.PlanValidator;
import com.nexora.reports.plan.domain.StoredPlan;
import com.nexora.reports.plan.domain.ValidatedPlan;
import com.nexora.reports.translation.domain.QueryTranslator;
import com.nexora.reports.translation.domain.SqlQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Orchestrates one execution (R1). Order of the checks, each failing closed:
 * <ol>
 *   <li>the requester comes from a validated token; AI principals are refused (no delegation policy exists);</li>
 *   <li>read-only credentials exist for exactly (requester tenant, data source);</li>
 *   <li>Semantic, called with the tenant's REPORTS_CLIENT service identity, confirms the data source is the one
 *       requested, PostgreSQL and ACTIVE;</li>
 *   <li>the plan is fetched by id from Semantic (never accepted from the client); its id, data source and tenant
 *       must match the request;</li>
 *   <li>the document is validated strictly ({@link PlanValidator});</li>
 *   <li>validity is checked ({@link ValidityPolicy}): STALE refused, CURRENT_WITH_CHANGES only if non-blocking;</li>
 *   <li>the plan is translated ({@link QueryTranslator}) and executed read-only after a live schema check;</li>
 *   <li>validity is checked again after the query: if the plan stopped being executable meanwhile, the rows are
 *       discarded (this narrows, but cannot close, the race between Semantic's catalog and the live database);</li>
 *   <li>the execution is audited. Results are only returned after a successful audit write.</li>
 * </ol>
 */
public class ReportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ReportExecutionService.class);

    private final PlanSource planSource;
    private final CredentialProvider credentials;
    private final ReadOnlyExecutor executor;
    private final AuditPort audit;
    private final ExecutionLimits limits;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final PlanValidator validator = new PlanValidator();
    private final QueryTranslator translator = new QueryTranslator();
    private final ValidityPolicy validityPolicy = new ValidityPolicy();

    public ReportExecutionService(PlanSource planSource, CredentialProvider credentials, ReadOnlyExecutor executor,
                                  AuditPort audit, ExecutionLimits limits, Clock clock, Supplier<UUID> ids) {
        this.planSource = Objects.requireNonNull(planSource);
        this.credentials = Objects.requireNonNull(credentials);
        this.executor = Objects.requireNonNull(executor);
        this.audit = Objects.requireNonNull(audit);
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public ExecutionLimits limits() {
        return limits;
    }

    /** What is known about the execution so far, for the audit of failures. */
    private static final class Trace {
        String fingerprint;
        String validity;
    }

    public ReportResult execute(Requester requester, UUID dataSourceId, UUID planId, Integer requestedMaxRows) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(planId, "planId");
        UUID executionId = ids.get();
        Instant startedAt = clock.instant();
        Trace trace = new Trace();
        ReportResult result;
        try {
            result = run(executionId, requester, dataSourceId, planId, requestedMaxRows, startedAt, trace);
        } catch (PlanNotExecutableException e) {
            auditFailure(executionId, requester, dataSourceId, planId, trace, Outcome.REJECTED,
                    "PLAN_NOT_EXECUTABLE", startedAt);
            throw e;
        } catch (ExecutionException e) {
            auditFailure(executionId, requester, dataSourceId, planId, trace,
                    e.code().rejection() ? Outcome.REJECTED : Outcome.FAILED, e.code().name(), startedAt);
            throw e;
        } catch (RuntimeException e) {
            auditFailure(executionId, requester, dataSourceId, planId, trace, Outcome.FAILED,
                    ErrorCode.EXECUTION_FAILED.name(), startedAt);
            throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "unexpected failure", e);
        }
        try {
            audit.record(new ExecutionAuditRecord(executionId, requester.tenantId(), dataSourceId, planId,
                    requester.subject(), requester.type().name(), result.planFingerprint(), result.validity().name(),
                    Outcome.SUCCEEDED, null, result.rowCount(), result.truncated(), result.durationMillis(),
                    startedAt));
        } catch (RuntimeException e) {
            // Without an audit record the result is not released.
            throw new ExecutionException(ErrorCode.AUDIT_UNAVAILABLE, "audit write failed", e);
        }
        return result;
    }

    private ReportResult run(UUID executionId, Requester requester, UUID dataSourceId, UUID planId,
                             Integer requestedMaxRows, Instant startedAt, Trace trace) {
        UUID tenantId = requester.tenantId();
        if (requester.type() == Requester.PrincipalType.AI) {
            throw new ExecutionException(ErrorCode.REQUESTER_NOT_ALLOWED, "AI principals cannot execute reports");
        }
        DataSourceCredentials connection = credentials.resolve(tenantId, dataSourceId)
                .orElseThrow(() -> new ExecutionException(ErrorCode.DATA_SOURCE_NOT_CONFIGURED,
                        "no read-only credentials for tenant and data source"));
        if (!connection.tenantId().equals(tenantId) || !connection.dataSourceId().equals(dataSourceId)) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_NOT_CONFIGURED, "credentials of another source");
        }

        DataSourceDescriptor source = planSource.dataSource(tenantId, dataSourceId);
        if (!dataSourceId.equals(source.id())) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "data source id mismatch");
        }
        if (!DataSourceDescriptor.POSTGRESQL.equals(source.engine())
                || !DataSourceDescriptor.ACTIVE.equals(source.status())) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_NOT_EXECUTABLE, "engine or status not executable");
        }

        StoredPlan stored = planSource.plan(tenantId, dataSourceId, planId);
        if (!planId.equals(stored.id()) || !dataSourceId.equals(stored.dataSourceId())) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "plan or data source id mismatch");
        }
        trace.fingerprint = stored.fingerprint();
        if (stored.plan() != null && stored.plan().tenantId() != null
                && !tenantId.equals(stored.plan().tenantId())) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "plan of another tenant");
        }
        ValidatedPlan plan = validator.validate(stored);
        if (!tenantId.equals(plan.tenantId()) || !dataSourceId.equals(plan.dataSourceId())) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "plan identity mismatch");
        }

        ValidityPolicy.Decision before = validityPolicy.decide(planId,
                planSource.validity(tenantId, dataSourceId, planId));
        trace.validity = before.status().name();

        int maxRows = limits.effectiveMaxRows(requestedMaxRows);
        SqlQuery query = translator.translate(plan, maxRows);
        QueryResult rows = executor.execute(connection, query, limits);

        ValidityPolicy.Decision after;
        try {
            after = validityPolicy.decide(planId, planSource.validity(tenantId, dataSourceId, planId));
        } catch (ExecutionException e) {
            if (e.code() == ErrorCode.PLAN_STALE) {
                trace.validity = "STALE";
                throw new ExecutionException(ErrorCode.PLAN_CHANGED_DURING_EXECUTION,
                        "plan became STALE during execution; rows discarded");
            }
            throw e;
        }
        trace.validity = after.status().name();

        List<ExecutionWarning> warnings = new ArrayList<>(after.warnings());
        if (rows.truncated()) {
            warnings.add(new ExecutionWarning(ExecutionWarning.Code.RESULT_TRUNCATED,
                    "The result has more rows than the limit; only the first " + maxRows + " are returned."));
        }
        long duration = clock.millis() - startedAt.toEpochMilli();
        log.info("execution {} succeeded: rows={} truncated={} validity={} durationMs={}", executionId,
                rows.rows().size(), rows.truncated(), after.status(), duration);
        return new ReportResult(executionId, planId, dataSourceId, stored.fingerprint(), after.status(),
                query.columns(), rows.rows(), rows.truncated(), maxRows, Math.max(duration, 0), warnings);
    }

    private void auditFailure(UUID executionId, Requester requester, UUID dataSourceId, UUID planId, Trace trace,
                              Outcome outcome, String errorCode, Instant startedAt) {
        long duration = Math.max(clock.millis() - startedAt.toEpochMilli(), 0);
        log.warn("execution {} {}: {}", executionId, outcome, errorCode);
        try {
            audit.record(new ExecutionAuditRecord(executionId, requester.tenantId(), dataSourceId, planId,
                    requester.subject(), requester.type().name(), trace.fingerprint, trace.validity, outcome,
                    errorCode, null, false, duration, startedAt));
        } catch (RuntimeException e) {
            // The original error is what the client must see; the missing audit is logged loudly.
            log.error("execution {}: audit write failed for a {} execution", executionId, outcome, e);
        }
    }
}
