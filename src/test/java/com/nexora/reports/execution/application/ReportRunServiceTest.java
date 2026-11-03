package com.nexora.reports.execution.application;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.SourceTarget;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.PlanV2Executor;
import com.nexora.reports.execution.application.port.ReportPlanSource;
import com.nexora.reports.execution.application.port.ReportRenderer;
import com.nexora.reports.execution.application.port.SourceAvailability;
import com.nexora.reports.execution.application.port.TenantScopes;
import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.RunException;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.PlanV2Validator;
import com.nexora.reports.plan2.domain.ReportPlanValidity;
import com.nexora.reports.plan2.domain.ReportsCeilings;
import com.nexora.reports.plan2.domain.SemanticDataSource;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.render.xlsx.XlsxRenderer;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.Fact;
import com.nexora.reports.result.domain.PresentationException;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.infrastructure.ArtifactStaging;
import com.nexora.reports.run.infrastructure.StagingDirectory;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B9 (D-B9-1): the run service step by step, with the real validator (B2), translator (B3), assembler (B6),
 * Excel renderer (B7), staging directory (B8) and contract schemas, and doubles for Semantic, the read (B4/B5) and the
 * registry. Every failure of every step ends as the documented answer, and never as a partial or unrecorded run.
 * <p>
 * B10-O3 (DS-20): the doubles of {@link SourceAvailability} and {@link CredentialProvider} count separately the
 * destinations looked up without their secret and the credentials resolved, and every call is recorded in order
 * ({@code events}) together with the validity checks and the reads. A change of configuration between the start of
 * a run and its read can only be reproduced here, with doubles: the configured provider of Reports never changes
 * while the application runs.
 */
class ReportRunServiceTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 100, Duration.ofSeconds(2));
    static final ReportRunDocuments DOCUMENTS = new ReportRunDocuments(
            RunContractSchemas.reportRun(ContractFixtures.READER),
            RunContractSchemas.reportArtifact(ContractFixtures.READER));

    private ContractFixtures.PaA documents;
    private UUID tenant;
    private UUID source;
    private final UUID reportPlanId = UUID.fromString("b9000000-0000-4000-8000-000000000001");
    private Requester human;
    private FakeSemantic semantic;
    private InMemoryRunRegistry registry;
    private Optional<DataSourceCredentials> credentials;
    /** What {@link SourceAvailability} answers; by default the destination of {@link #credentials}. */
    private Optional<SourceTarget> available;
    private RuntimeException availabilityFailure;
    private RuntimeException resolveFailure;
    private int availabilityQueries;
    private int secretResolutions;
    private DataSourceCredentials readWith;
    private final List<String> events = new ArrayList<>();
    private List<List<List<Object>>> rows;
    private List<Boolean> truncated;
    private RuntimeException readFailure;
    private int reads;
    private int scopeChecks;
    private ReportResult rendered;
    private PresentationException renderFailure;
    private Path stagingRoot;
    private ReportRunService service;

    @BeforeEach
    void setUp() throws IOException {
        documents = new ContractFixtures.PaA();
        tenant = documents.tenantId;
        source = documents.dataSourceId;
        human = new Requester("analyst-1", Requester.PrincipalType.HUMAN, tenant);
        semantic = new FakeSemantic();
        registry = new InMemoryRunRegistry();
        credentials = Optional.of(new DataSourceCredentials(tenant, source, "db.example", 5432, "bench", "reader",
                "secret", "verify-full", 2));
        available = credentials.map(DataSourceCredentials::target);
        availabilityFailure = null;
        resolveFailure = null;
        availabilityQueries = 0;
        secretResolutions = 0;
        readWith = null;
        events.clear();
        rows = RenderFixtures.paARows();
        truncated = RenderFixtures.complete(3);
        readFailure = null;
        reads = 0;
        scopeChecks = 0;
        rendered = null;
        renderFailure = null;
        stagingRoot = Files.createTempDirectory("b9-staging");
        service = service(4);
    }

    @AfterEach
    void cleanUp() throws IOException {
        try (Stream<Path> files = Files.list(stagingRoot)) {
            for (Path p : files.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(stagingRoot);
    }

    private ReportRunService service(int downloads) throws IOException {
        PlanV2Executor executor = (credentials, request, budget, scope) -> {
            reads++;
            readWith = credentials;
            events.add("read");
            if (readFailure != null) {
                throw readFailure;
            }
            List<DatasetRows> read = new ArrayList<>();
            for (int i = 0; i < request.queries().size(); i++) {
                SqlQueryV2 q = request.queries().get(i);
                read.add(new DatasetRows(q.datasetId(), q.outputs(), rows.get(i), truncated.get(i),
                        Duration.ofMillis(5 + i)));
            }
            return new SnapshotRead("7841:7841:", read, Duration.ofMillis(30));
        };
        TenantScopes scopes = request -> {
            scopeChecks++;
            return (c, catalog) -> { };
        };
        XlsxRenderer xlsx = new XlsxRenderer();
        ReportRenderer renderer = (result, out, limits) -> {
            rendered = result;
            if (renderFailure != null) {
                throw renderFailure;
            }
            return xlsx.render(result, out, limits);
        };
        ReportRunService.Settings settings = new ReportRunService.Settings(Duration.ofSeconds(120), LIMITS,
                64L * 1024 * 1024, AssemblyLimits.DEFAULTS, RenderLimits.DEFAULTS, 3, "0.1.0-test", downloads);
        SourceAvailability availability = (t, d) -> {
            availabilityQueries++;
            events.add("configured");
            if (availabilityFailure != null) {
                throw availabilityFailure;
            }
            return available.filter(c -> c.tenantId().equals(t) && c.dataSourceId().equals(d));
        };
        CredentialProvider provider = (t, d) -> {
            secretResolutions++;
            events.add("resolve");
            if (resolveFailure != null) {
                throw resolveFailure;
            }
            return credentials.filter(c -> c.tenantId().equals(t) && c.dataSourceId().equals(d));
        };
        return new ReportRunService(semantic, availability, provider, new PlanV2Validator(ContractFixtures.schemas(),
                ReportsCeilings.DEFAULTS, ContractFixtures.SHA256), ContractFixtures.SHA256, new PlanV2Translator(),
                executor, scopes, new ResultAssembler(), renderer,
                new StagingDirectory(new ArtifactStaging(stagingRoot)), registry, registry, registry, registry,
                DOCUMENTS, settings, Clock.fixed(Instant.parse("2026-11-01T16:42:04Z"), ZoneOffset.UTC),
                UUID::randomUUID);
    }

    /** Semantic as A3 serves it, scoped by tenant, with a queue of validity answers (before, after). */
    private final class FakeSemantic implements ReportPlanSource {
        SemanticDataSource dataSource;
        RuntimeException reportPlanFailure;
        final Deque<Object> validities = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        FakeSemantic() {
            dataSource = new SemanticDataSource(source, "postgresql", "ACTIVE", Optional.of("Banco sintético"));
        }

        private void check(UUID t, UUID d) {
            if (!tenant.equals(t) || !source.equals(d)) {
                throw new ExecutionException(ErrorCode.DATA_SOURCE_NOT_FOUND, "other tenant or source");
            }
        }

        @Override
        public SemanticDataSource dataSource(UUID t, UUID d) {
            calls.add("data-source");
            check(t, d);
            return dataSource;
        }

        @Override
        public Map<String, Object> reportPlan(UUID t, UUID d, UUID rp) {
            calls.add("report-plan");
            check(t, d);
            if (reportPlanFailure != null) {
                throw reportPlanFailure;
            }
            if (!reportPlanId.equals(rp)) {
                throw new ExecutionException(ErrorCode.PLAN_NOT_FOUND, "no such report plan");
            }
            return ContractFixtures.copy(documents.reportPlan);
        }

        @Override
        public Map<String, Object> spec(UUID t, UUID d, UUID rp) {
            calls.add("spec");
            check(t, d);
            return ContractFixtures.copy(documents.spec);
        }

        @Override
        public Map<String, Object> logicalPlan(UUID t, UUID d, UUID rp, UUID planId) {
            calls.add("logical-plan");
            check(t, d);
            Map<String, Object> plan = documents.plans.get(planId);
            if (plan == null) {
                throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "no such plan");
            }
            return ContractFixtures.copy(plan);
        }

        @Override
        public ReportPlanValidity validity(UUID t, UUID d, UUID rp) {
            calls.add("validity");
            events.add("validity");
            check(t, d);
            Object next = validities.isEmpty() ? current() : validities.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (ReportPlanValidity) next;
        }
    }

    private static ReportPlanValidity current() {
        return new ReportPlanValidity(ReportPlanValidity.Status.CURRENT, List.of());
    }

    private static ReportPlanValidity stale() {
        return new ReportPlanValidity(ReportPlanValidity.Status.STALE, List.of("OBJECT_CHANGED"));
    }

    private ReportRunService.RunReport run() {
        return service.run(human, source, reportPlanId);
    }

    private static Map<String, Object> refusal(ReportRunService.RunReport report) {
        return m(report.document().get("refusal"));
    }

    // ------------------------------------------------------------------ delivered runs

    @Test
    void aCompletedRunIsAValidReportRunWithItsStoredArtifact() {
        ReportRunService.RunReport report = run();

        Map<String, Object> doc = report.document();
        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("status")).isEqualTo("COMPLETED");
        assertThat(doc.get("runId")).isEqualTo(report.runId().toString());
        assertThat(doc.get("reportPlanFingerprint")).isEqualTo(documents.reportPlan.get("fingerprint"));
        assertThat(m(doc.get("consistency")).get("snapshot")).isEqualTo("7841:7841:");
        assertThat(l(doc.get("datasets"))).hasSize(3);
        assertThat(doc.get("refusal")).isNull();
        Map<String, Object> artifact = m(doc.get("artifact"));
        byte[] stored = registry.runs.get(report.runId()).content();
        assertThat(artifact.get("sha256")).isEqualTo(InMemoryRunRegistry.sha256(stored));
        assertThat(artifact.get("sizeBytes")).isEqualTo((long) stored.length);
        assertThat(l(artifact.get("sheets")).getFirst()).isEqualTo("Reporte");
        assertThat(registry.kind(report.runId())).isEqualTo(RunOutcomeKind.COMPLETED);
        assertThat(reads).isEqualTo(1);
        assertThat(scopeChecks).isEqualTo(1);
        assertThat(semantic.calls).containsExactly("data-source", "report-plan", "spec", "logical-plan",
                "logical-plan", "logical-plan", "validity", "validity");
        // B10-O3: the destination first, the credentials only after the validity before reading and right before
        // the read, once; the read uses exactly the resolved credentials.
        assertThat(events).containsExactly("configured", "validity", "resolve", "read", "validity");
        assertThat(availabilityQueries).isEqualTo(1);
        assertThat(secretResolutions).isEqualTo(1);
        assertThat(readWith).isEqualTo(credentials.orElseThrow());
    }

    @Test
    void theSourceNameFromSemanticIsAKnownFactOfTheHeader() {
        run();

        assertThat(rendered.header().sourceName()).isEqualTo(Fact.known("Banco sintético"));
        assertThat(rendered.header().reportsVersion()).isEqualTo("0.1.0-test");
    }

    @Test
    void readingTheRunGivesExactlyTheDocumentOfItsAnswer() {
        ReportRunService.RunReport report = run();

        assertThat(service.find(human, source, report.runId()).document()).isEqualTo(report.document());
    }

    @Test
    void aTruncatedDatasetMakesThePartialRunWithAGeneratedWarning() {
        for (Object op : documents.operations("detalle")) {
            if ("LIMIT".equals(m(op).get("op"))) {
                m(op).put("maxRows", 3L);
            }
        }
        documents.reseal();
        rows.set(2, new ArrayList<>(rows.get(2).subList(0, 3)));
        truncated = List.of(false, false, true);

        Map<String, Object> doc = run().document();

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("status")).isEqualTo("PARTIAL");
        Map<String, Object> warning = m(l(doc.get("warnings")).getFirst());
        assertThat(warning.get("code")).isEqualTo("dataset_truncated");
        assertThat(warning.get("datasetId")).isEqualTo("detalle");
        assertThat((String) warning.get("message")).contains("3");
        assertThat(doc.get("artifact")).isNotNull();
    }

    @Test
    void theStagedFileIsDeletedAfterTheRun() throws IOException {
        run();

        try (Stream<Path> files = Files.list(stagingRoot)) {
            assertThat(files.count()).isEqualTo(0L);
        }
    }

    // ------------------------------------------------------------------ steps 1 to 3: no run is started

    @Test
    void anAiPrincipalRunsNothingAndStartsNothing() {
        Requester ai = new Requester("agent", Requester.PrincipalType.AI, tenant);

        assertThatThrownBy(() -> service.run(ai, source, reportPlanId)).isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo(ErrorCode.REQUESTER_NOT_ALLOWED));
        assertThat(registry.runs).isEmpty();
        assertThat(semantic.calls).isEmpty();
    }

    @Test
    void withoutReadOnlyCredentialsNothingIsAskedOrStarted() {
        credentials = Optional.empty();
        available = Optional.empty();

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.DATA_SOURCE_NOT_CONFIGURED));
        assertThat(registry.runs).isEmpty();
        assertThat(semantic.calls).isEmpty();
        assertThat(availabilityQueries).isEqualTo(1);
        assertThat(secretResolutions).isZero();
    }

    /** B10-O3: the destination policy keeps its answer (500 data_source_target_not_allowed) before anything else. */
    @Test
    void aForbiddenDestinationIsRefusedBeforeAnythingIsAskedOrStarted() {
        availabilityFailure = new ExecutionException(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED, "not allowed");

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED));
        assertThat(registry.runs).isEmpty();
        assertThat(semantic.calls).isEmpty();
        assertThat(secretResolutions).isZero();
    }

    /** B10-O3: a destination that is not the requested (tenant, source) is never used, as in R1. */
    @Test
    void aDestinationOfAnotherSourceIsNotAConfiguredSource() throws IOException {
        available = Optional.of(new SourceTarget(tenant, UUID.randomUUID(), "db.example", 5432, "bench", "reader",
                "verify-full", 2, null));
        SourceAvailability any = (t, d) -> available;
        service = new ReportRunService(semantic, any, (t, d) -> credentials, new PlanV2Validator(
                ContractFixtures.schemas(), ReportsCeilings.DEFAULTS, ContractFixtures.SHA256), ContractFixtures.SHA256,
                new PlanV2Translator(), (c, r, b, s) -> { throw new AssertionError("read"); }, request -> null,
                new ResultAssembler(), (r, o, l) -> { throw new AssertionError("render"); },
                new StagingDirectory(new ArtifactStaging(stagingRoot)), registry, registry, registry, registry,
                DOCUMENTS, new ReportRunService.Settings(Duration.ofSeconds(120), LIMITS, 1024, AssemblyLimits.DEFAULTS,
                RenderLimits.DEFAULTS, 3, "0.1.0-test", 4), Clock.systemUTC(), UUID::randomUUID);

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.DATA_SOURCE_NOT_CONFIGURED));
        assertThat(registry.runs).isEmpty();
        assertThat(semantic.calls).isEmpty();
    }

    @Test
    void aSourceThatIsNotAnActivePostgresqlSourceIsRefusedBeforeStarting() {
        semantic.dataSource = new SemanticDataSource(source, "postgresql", "ARCHIVED", Optional.empty());

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.DATA_SOURCE_NOT_EXECUTABLE));
        assertThat(registry.runs).isEmpty();
    }

    @Test
    void aDataSourceDocumentOfAnotherSourceIsAContractViolationOfSemantic() {
        semantic.dataSource = new SemanticDataSource(UUID.randomUUID(), "postgresql", "ACTIVE", Optional.empty());

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.SEMANTIC_CONTRACT_VIOLATION));
        assertThat(registry.runs).isEmpty();
    }

    @Test
    void anUnreachableSemanticLeavesNoRun() {
        semantic.reportPlanFailure = new ExecutionException(ErrorCode.SEMANTIC_UNAVAILABLE, "down");

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.SEMANTIC_UNAVAILABLE));
        assertThat(registry.runs).isEmpty();
        assertThat(secretResolutions).isZero();
    }

    @Test
    void anUnknownReportPlanIsNotFound() {
        assertThatThrownBy(() -> service.run(human, source, UUID.randomUUID()))
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo(ErrorCode.PLAN_NOT_FOUND));
        assertThat(registry.runs).isEmpty();
    }

    @Test
    void aRegistryThatCannotStartTheRunReadsNothing() {
        registry.failBegin = true;

        assertThatThrownBy(this::run).satisfies(e -> assertThat(((ExecutionException) e).code())
                .isEqualTo(ErrorCode.RUN_STORAGE_UNAVAILABLE));
        assertThat(reads).isEqualTo(0);
    }

    // ------------------------------------------------------------------ steps 5 to 11: REFUSED or FAILED runs

    @Test
    void aSpecificationThatIsNotTheValidatedOneIsRefusedWithTheTrustedFingerprint() {
        documents.spec.put("title", "Otro título");

        ReportRunService.RunReport report = run();

        assertThat(DOCUMENTS.runVerdict(report.document())).isEqualTo("OK");
        assertThat(report.document().get("status")).isEqualTo("REFUSED");
        assertThat(refusal(report).get("code")).isEqualTo("fingerprint_mismatch");
        assertThat(report.document().get("reportPlanFingerprint")).isEqualTo(documents.reportPlan.get("fingerprint"));
        assertThat(report.document().get("artifact")).isNull();
        assertThat(reads).isEqualTo(0);
        assertThat(secretResolutions).isZero();
    }

    @Test
    void aReportPlanWhoseOwnFingerprintIsWrongIsRefusedWithoutAReportRunDocument() {
        documents.reportPlan.put("fingerprint", "0".repeat(64));

        RunException e = (RunException) org.assertj.core.api.Assertions.catchThrowable(this::run);

        assertThat(e.code()).isEqualTo(ErrorCode.REPORT_PLAN_INVALID);
        assertThat(registry.kind(e.runId())).isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(registry.runs.get(e.runId()).outcome().reportPlanFingerprint()).isNull();
        RunException read = (RunException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.find(human, source, e.runId()));
        assertThat(read.code()).isEqualTo(ErrorCode.REPORT_PLAN_INVALID);
        assertThat(reads).isEqualTo(0);
    }

    @Test
    void aReportPlanOfAnotherTenantIsRefusedAsAnIdentityMismatch() {
        documents.reportPlan.put("tenantId", UUID.randomUUID().toString());
        documents.reportPlan.put("fingerprint", com.nexora.reports.plan2.domain.CanonicalJson
                .fingerprintOf(documents.reportPlan, ContractFixtures.SHA256));

        ReportRunService.RunReport report = run();

        assertThat(refusal(report).get("code")).isEqualTo("identity_mismatch");
        assertThat(reads).isEqualTo(0);
        assertThat(secretResolutions).isZero();
    }

    @Test
    void aStalePlanIsRefusedBeforeAnythingIsRead() {
        semantic.validities.add(stale());

        ReportRunService.RunReport report = run();

        assertThat(refusal(report).get("code")).isEqualTo("plan_stale");
        assertThat(reads).isEqualTo(0);
        assertThat(DOCUMENTS.runVerdict(report.document())).isEqualTo("OK");
        // B10-O3 (DS-20): refused before any credentials are resolved.
        assertThat(availabilityQueries).isEqualTo(1);
        assertThat(secretResolutions).isZero();
        assertThat(events).containsExactly("configured", "validity");
    }

    @Test
    void aValidityThatCannotBeConfirmedIsRefusedNeverAssumed() {
        semantic.validities.add(new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "no answer"));

        assertThat(refusal(run()).get("code")).isEqualTo("plan_validity_unconfirmed");
        assertThat(reads).isEqualTo(0);
        assertThat(secretResolutions).isZero();
    }

    @Test
    void aPlanThatChangesDuringTheReadIsRefusedAndItsRowsDiscarded() {
        semantic.validities.add(current());
        semantic.validities.add(stale());

        ReportRunService.RunReport report = run();

        assertThat(refusal(report).get("code")).isEqualTo("plan_changed_during_execution");
        assertThat(report.document().get("artifact")).isNull();
        assertThat(reads).isEqualTo(1);
        assertThat(rendered).isNull();
        assertThat(secretResolutions).isEqualTo(1);
    }

    // ------------------------------------------------------------------ B10-O3: configuration between start and read

    /** The configuration disappeared after the run started: a refusal with the same code, nothing read. */
    @Test
    void credentialsThatDisappearAfterTheStartAreARefusalWithoutReading() {
        credentials = Optional.empty();

        ReportRunService.RunReport report = run();

        assertThat(DOCUMENTS.runVerdict(report.document())).isEqualTo("OK");
        assertThat(refusal(report).get("code")).isEqualTo("data_source_not_configured");
        assertThat(report.document().get("artifact")).isNull();
        assertThat(reads).isZero();
        assertThat(scopeChecks).isZero();
        assertThat(secretResolutions).isEqualTo(1);
        assertThat(registry.kind(report.runId())).isEqualTo(RunOutcomeKind.REFUSED);
    }

    /** The resolved credentials lead elsewhere than the destination of step 2: nothing is read. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("changedDestinations")
    void aDestinationThatChangedAfterTheStartIsRefusedWithoutReading(String component, DataSourceCredentials moved) {
        credentials = Optional.of(moved);

        ReportRunService.RunReport report = run();

        assertThat(DOCUMENTS.runVerdict(report.document())).isEqualTo("OK");
        assertThat(refusal(report).get("code")).isEqualTo("data_source_configuration_changed");
        assertThat(report.document().get("artifact")).isNull();
        assertThat(reads).isZero();
        assertThat(scopeChecks).isZero();
    }

    static Stream<Arguments> changedDestinations() {
        UUID t = new ContractFixtures.PaA().tenantId;
        UUID d = new ContractFixtures.PaA().dataSourceId;
        return Stream.of(
                Arguments.of("host", new DataSourceCredentials(t, d, "db2.example", 5432, "bench", "reader", "secret",
                        "verify-full", 2)),
                Arguments.of("host letter case", new DataSourceCredentials(t, d, "DB.example", 5432, "bench",
                        "reader", "secret", "verify-full", 2)),
                Arguments.of("port", new DataSourceCredentials(t, d, "db.example", 5433, "bench", "reader", "secret",
                        "verify-full", 2)),
                Arguments.of("database", new DataSourceCredentials(t, d, "db.example", 5432, "other", "reader",
                        "secret", "verify-full", 2)),
                Arguments.of("username", new DataSourceCredentials(t, d, "db.example", 5432, "bench", "writer",
                        "secret", "verify-full", 2)),
                Arguments.of("sslmode", new DataSourceCredentials(t, d, "db.example", 5432, "bench", "reader",
                        "secret", "require", 2)),
                Arguments.of("pool size", new DataSourceCredentials(t, d, "db.example", 5432, "bench", "reader",
                        "secret", "verify-full", 3)),
                Arguments.of("CA certificate", new DataSourceCredentials(t, d, "db.example", 5432, "bench", "reader",
                        "secret", "verify-full", 2, "/run/secrets/other-ca.crt")));
    }

    /**
     * A new password alone is not a change of destination (the password is not part of {@link SourceTarget}): the
     * run reads with it. Whether the pool of the source then uses it is the separate observation B10-O10.
     */
    @Test
    void aChangedPasswordAloneIsNotAChangeOfDestination() {
        credentials = Optional.of(new DataSourceCredentials(tenant, source, "db.example", 5432, "bench", "reader",
                "rotated", "verify-full", 2));

        ReportRunService.RunReport report = run();

        assertThat(report.document().get("status")).isEqualTo("COMPLETED");
        assertThat(readWith.password()).isEqualTo("rotated");
    }

    /** A destination no longer allowed when resolving keeps its documented code, as a refusal of the started run. */
    @Test
    void aDestinationNoLongerAllowedWhenResolvingIsARefusalWithoutReading() {
        resolveFailure = new ExecutionException(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED, "not allowed");

        ReportRunService.RunReport report = run();

        assertThat(DOCUMENTS.runVerdict(report.document())).isEqualTo("OK");
        assertThat(refusal(report).get("code")).isEqualTo("data_source_target_not_allowed");
        assertThat(reads).isZero();
    }

    /** An unexpected failure of the credential provider is an internal failure, recorded once; nothing is read. */
    @Test
    void anUnexpectedFailureOfTheCredentialProviderIsAnInternalFailure() {
        resolveFailure = new IllegalStateException("vault down");

        RunException e = (RunException) org.assertj.core.api.Assertions.catchThrowable(this::run);

        assertThat(e.code()).isEqualTo(ErrorCode.RUN_FAILED);
        assertThat(registry.runs.get(e.runId()).outcome().failureCode().code()).isEqualTo("internal_error");
        assertThat(reads).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT_SCOPE_UNVERIFIED", "PHYSICAL_SCHEMA_MISMATCH", "TOO_MANY_EXECUTIONS",
            "RESULT_TOO_LARGE", "DATA_SOURCE_UNAVAILABLE", "EXECUTION_TIMEOUT", "DATA_SOURCE_ROLE_NOT_READ_ONLY"})
    void aReadThatIsRefusedOrFailsIsARefusedRunWithItsStableCode(String code) {
        readFailure = new ExecutionException(ErrorCode.valueOf(code), "read refused");

        ReportRunService.RunReport report = run();

        assertThat(DOCUMENTS.runVerdict(report.document())).isEqualTo("OK");
        assertThat(refusal(report).get("code")).isEqualTo(code.toLowerCase(java.util.Locale.ROOT));
        assertThat(report.document().get("artifact")).isNull();
        assertThat(rendered).isNull();
    }

    @Test
    void overTheTenantQuotaTheRunIsRefusedAndKeepsNoArtifact() {
        registry.overQuota = true;

        ReportRunService.RunReport report = run();

        assertThat(refusal(report).get("code")).isEqualTo("artifact_quota_exceeded");
        assertThat(report.document().get("artifact")).isNull();
        assertThat(registry.runs.get(report.runId()).content()).isNull();
    }

    @Test
    void anArtifactOverItsSizeLimitIsARefusal() {
        renderFailure = new PresentationException(PresentationException.Code.ARTIFACT_TOO_LARGE, "too large");

        assertThat(refusal(run()).get("code")).isEqualTo("artifact_too_large");
    }

    @Test
    void noFreePresentationSlotIsARefusalToRetry() {
        renderFailure = new PresentationException(PresentationException.Code.TOO_MANY_PRESENTATIONS, "busy");

        assertThat(refusal(run()).get("code")).isEqualTo("too_many_executions");
    }

    @Test
    void aRenderingFailureIsAFailedRunWithoutDocumentButWithItsId() {
        renderFailure = new PresentationException(PresentationException.Code.RENDER_FAILED, "io");

        RunException e = (RunException) org.assertj.core.api.Assertions.catchThrowable(this::run);

        assertThat(e.code()).isEqualTo(ErrorCode.RUN_FAILED);
        assertThat(registry.kind(e.runId())).isEqualTo(RunOutcomeKind.FAILED);
        assertThat(registry.runs.get(e.runId()).outcome().failureCode().code()).isEqualTo("render_failed");
        RunException read = (RunException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.find(human, source, e.runId()));
        assertThat(read.code()).isEqualTo(ErrorCode.RUN_RECORDED_AS_FAILED);
    }

    @Test
    void anUnexpectedErrorIsRecordedAsAnInternalFailureOnce() {
        readFailure = new IllegalStateException("bug");

        RunException e = (RunException) org.assertj.core.api.Assertions.catchThrowable(this::run);

        assertThat(e.code()).isEqualTo(ErrorCode.RUN_FAILED);
        assertThat(registry.runs.get(e.runId()).outcome().failureCode().code()).isEqualTo("internal_error");
        assertThat(registry.events.stream().filter("RUN_FINISHED"::equals).count()).isEqualTo(1L);
    }

    @Test
    void aRegistryThatCannotRecordTheOutcomeAnswersWithTheRunIdForTheReconciliation() {
        registry.failFinish = true;

        RunException e = (RunException) org.assertj.core.api.Assertions.catchThrowable(this::run);

        assertThat(e.code()).isEqualTo(ErrorCode.RUN_STORAGE_UNAVAILABLE);
        assertThat(registry.runs.get(e.runId()).outcome()).isNull();
    }

    // ------------------------------------------------------------------ reads of a run

    @Test
    void aRunOfAnotherOwnerSourceOrTenantIsNotFound() {
        UUID runId = run().runId();

        for (Requester other : List.of(new Requester("analyst-2", Requester.PrincipalType.HUMAN, tenant),
                new Requester("analyst-1", Requester.PrincipalType.HUMAN, UUID.randomUUID()))) {
            assertThatThrownBy(() -> service.find(other, source, runId)).satisfies(e -> assertThat(
                    ((ExecutionException) e).code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
        }
        assertThatThrownBy(() -> service.find(human, UUID.randomUUID(), runId)).satisfies(e -> assertThat(
                ((ExecutionException) e).code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.find(human, source, UUID.randomUUID())).satisfies(e -> assertThat(
                ((ExecutionException) e).code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
    }

    @Test
    void aRunWithoutOutcomeIsInProgressUntilItsDeadlineAndInterruptedAfter() {
        UUID runId = UUID.randomUUID();
        registry.begin(new com.nexora.reports.run.domain.RunStart(runId, tenant, source, reportPlanId,
                new com.nexora.reports.run.domain.RunOwner("analyst-1", com.nexora.reports.run.domain.RunOwner.Type.HUMAN),
                Duration.ofSeconds(120)));

        RunException inProgress = (RunException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.find(human, source, runId));
        registry.overdue = true;
        RunException interrupted = (RunException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.find(human, source, runId));

        assertThat(inProgress.code()).isEqualTo(ErrorCode.RUN_IN_PROGRESS);
        assertThat(interrupted.code()).isEqualTo(ErrorCode.RUN_INTERRUPTED);
    }

    // ------------------------------------------------------------------ downloads

    @Test
    void aDownloadWritesTheVerifiedBytesAndRecordsTheDeliveryAfterThem() throws IOException {
        UUID runId = run().runId();
        List<String> seen = new ArrayList<>();

        service.download(human, source, runId, (artifact, content) -> {
            seen.add(artifact.sha256());
            seen.add(InMemoryRunRegistry.sha256(content));
            assertThat(registry.events.contains("ARTIFACT_DELIVERED")).isFalse();
        });

        assertThat(seen.get(0)).isEqualTo(seen.get(1));
        assertThat(registry.events.getLast()).isEqualTo("ARTIFACT_DELIVERED");
    }

    @Test
    void aClientThatGoesAwayLeavesADeliveryFailure() {
        UUID runId = run().runId();

        assertThatThrownBy(() -> service.download(human, source, runId, (artifact, content) -> {
            throw new IOException("broken pipe");
        })).isInstanceOf(IOException.class);
        assertThat(registry.events.getLast()).isEqualTo("ARTIFACT_DELIVERY_FAILED:client_write_failed");
        assertThat(registry.events.contains("ARTIFACT_DELIVERED")).isFalse();
    }

    @Test
    void anExpiredArtifactIsGoneAndACorruptOneIsNeverDelivered() {
        UUID runId = run().runId();
        registry.expired = true;
        RunException gone = (RunException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.download(human, source, runId, (a, c) -> { throw new AssertionError("written"); }));
        registry.expired = false;
        registry.corrupt = true;
        RunException corrupt = (RunException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.download(human, source, runId, (a, c) -> { throw new AssertionError("written"); }));

        assertThat(gone.code()).isEqualTo(ErrorCode.ARTIFACT_GONE);
        assertThat(corrupt.code()).isEqualTo(ErrorCode.ARTIFACT_INTEGRITY_FAILED);
    }

    @Test
    void theArtifactOfAnotherOwnerOrOfARefusedRunIsNotFound() {
        UUID runId = run().runId();
        Requester other = new Requester("analyst-2", Requester.PrincipalType.HUMAN, tenant);
        semantic.validities.add(stale());
        UUID refused = run().runId();

        assertThatThrownBy(() -> service.download(other, source, runId, (a, c) -> { })).satisfies(e -> assertThat(
                ((ExecutionException) e).code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.download(human, source, refused, (a, c) -> { })).satisfies(e -> assertThat(
                ((ExecutionException) e).code()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
    }

    @Test
    void downloadsBeyondTheirLimitAreRefusedToRetry() throws IOException {
        service = service(1);
        UUID runId = run().runId();
        List<ErrorCode> nested = new ArrayList<>();

        service.download(human, source, runId, (artifact, content) -> {
            try {
                service.download(human, source, runId, (a, c) -> { });
            } catch (ExecutionException e) {
                nested.add(e.code());
            }
        });
        service.download(human, source, runId, (a, c) -> { });

        assertThat(nested).containsExactly(ErrorCode.TOO_MANY_DOWNLOADS);
    }

    @Test
    void anAiPrincipalNeitherReadsNorDownloads() {
        UUID runId = run().runId();
        Requester ai = new Requester("analyst-1", Requester.PrincipalType.AI, tenant);

        assertThatThrownBy(() -> service.find(ai, source, runId)).satisfies(e -> assertThat(
                ((ExecutionException) e).code()).isEqualTo(ErrorCode.REQUESTER_NOT_ALLOWED));
        assertThatThrownBy(() -> service.download(ai, source, runId, (a, c) -> { })).satisfies(e -> assertThat(
                ((ExecutionException) e).code()).isEqualTo(ErrorCode.REQUESTER_NOT_ALLOWED));
    }
}
