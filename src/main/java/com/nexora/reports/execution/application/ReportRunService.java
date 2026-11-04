package com.nexora.reports.execution.application;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.SourceTarget;
import com.nexora.reports.execution.application.port.ArtifactDeliveries;
import com.nexora.reports.execution.application.port.ArtifactReader;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.PlanV2Executor;
import com.nexora.reports.execution.application.port.ReportPlanSource;
import com.nexora.reports.execution.application.port.ReportRenderer;
import com.nexora.reports.execution.application.port.RunReader;
import com.nexora.reports.execution.application.port.RunStore;
import com.nexora.reports.execution.application.port.SourceAvailability;
import com.nexora.reports.execution.application.port.StagingArea;
import com.nexora.reports.execution.application.port.TenantScopes;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RedactedFailure;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.RunException;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.domain.PlanV2Exception;
import com.nexora.reports.plan2.domain.PlanV2Validator;
import com.nexora.reports.plan2.domain.ReportPlanValidity;
import com.nexora.reports.plan2.domain.SemanticDataSource;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.domain.AssemblyInput;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.PresentationException;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.result.domain.ResultAssemblyException;
import com.nexora.reports.result.domain.ResultWarning;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.run.domain.FailureCode;
import com.nexora.reports.run.domain.FinishResult;
import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOwner;
import com.nexora.reports.run.domain.RunRef;
import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunStart;
import com.nexora.reports.run.domain.RunStoreException;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StoredArtifact;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A4-B9 (D-B9-1): runs a report plan compiled by Nexora Semantic and serves the run and its artifact to its owner.
 * Synchronous; every step fails closed (A4 proposal v3, section 4):
 * <ol>
 *   <li>the requester comes from the validated token: a person or a service, never an AI principal;</li>
 *   <li>a read-only connection is configured for (tenant, data source), within the destination policy: its
 *       destination is looked up <b>without resolving its secret</b> (B10-O3);</li>
 *   <li>Semantic, with the tenant's REPORTS_CLIENT identity: the source must be an active PostgreSQL source; the
 *       report plan, its specification and its logical plans are read;</li>
 *   <li>the run is started in the registry (B8): from here every run leaves an outcome;</li>
 *   <li>independent validation of every document (B2);</li>
 *   <li>validity before reading: a STALE plan is refused ({@code plan_stale});</li>
 *   <li>translation of every dataset (B3), and only then, because a read follows, the credentials themselves: they
 *       must still exist, be allowed and lead to exactly the destination of step 2, otherwise the run is refused
 *       ({@code data_source_not_configured}, {@code data_source_target_not_allowed},
 *       {@code data_source_configuration_changed}) without reading (B10-O3);</li>
 *   <li>one REPEATABLE READ, read-only snapshot (B4) with the mandatory tenant scope check (B5, {@code verify-full})
 *       and the live schema check;</li>
 *   <li>validity after reading: a plan that stopped being current is refused
 *       ({@code plan_changed_during_execution}) and the rows are discarded;</li>
 *   <li>assembly (B6) and presentation (B7) into Reports' staging directory;</li>
 *   <li>the outcome and the artifact are stored (B8): quota and size are enforced and nothing partial remains.</li>
 * </ol>
 * Failures before step 4 are answered as errors and leave no run. After it, a refusal is a REFUSED run and an
 * internal failure a FAILED one. The answer is always rebuilt from the registry, exactly as a later read.
 */
public class ReportRunService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportRunService.class);
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    /**
     * Limits of a run.
     *
     * @param maxDuration            deadline of a run; without an outcome by then it is INTERRUPTED (120 s by default)
     * @param limits                 server limits of the read (shared with R1)
     * @param maxEstimatedBytes      memory budget of the rows of one run
     * @param assembly               cell budget of a report
     * @param render                 size limit and slot wait of a presentation
     * @param maxPlans               logical plans that are fetched at most for one report plan (the validator's ceiling)
     * @param reportsVersion         stated in the report header
     * @param maxConcurrentDownloads simultaneous artifact downloads (B8-O3)
     */
    public record Settings(Duration maxDuration, ExecutionLimits limits, long maxEstimatedBytes,
                           AssemblyLimits assembly, RenderLimits render, int maxPlans, String reportsVersion,
                           int maxConcurrentDownloads) {
        public static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(120);
        public static final int DEFAULT_MAX_CONCURRENT_DOWNLOADS = 4;

        public Settings {
            Objects.requireNonNull(maxDuration, "maxDuration");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(assembly, "assembly");
            Objects.requireNonNull(render, "render");
            Objects.requireNonNull(reportsVersion, "reportsVersion");
            if (maxDuration.compareTo(RunStart.MIN_DURATION) < 0 || maxDuration.compareTo(RunStart.MAX_DURATION) > 0
                    || maxEstimatedBytes < 1 || maxPlans < 1 || maxConcurrentDownloads < 1) {
                throw new IllegalArgumentException("invalid run settings");
            }
        }
    }

    /** Where the bytes of an artifact are written for a client; an {@link IOException} means it was not delivered. */
    @FunctionalInterface
    public interface ArtifactSink {
        void write(StoredArtifact artifact, byte[] content) throws IOException;
    }

    /** A recorded run and its {@code nexora-report-run/1}. */
    public record RunReport(UUID runId, Map<String, Object> document) {
        public RunReport {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(document, "document");
        }
    }

    private final ReportPlanSource semantic;
    private final SourceAvailability availability;
    private final CredentialProvider credentials;
    private final PlanV2Validator validator;
    private final Sha256 sha256;
    private final PlanV2Translator translator;
    private final PlanV2Executor executor;
    private final TenantScopes tenantScopes;
    private final ResultAssembler assembler;
    private final ReportRenderer renderer;
    private final StagingArea staging;
    private final RunStore store;
    private final RunReader runs;
    private final ArtifactReader artifacts;
    private final ArtifactDeliveries deliveries;
    private final ReportRunDocuments documents;
    private final Settings settings;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final Semaphore downloads;

    public ReportRunService(ReportPlanSource semantic, SourceAvailability availability,
                            CredentialProvider credentials, PlanV2Validator validator, Sha256 sha256,
                            PlanV2Translator translator, PlanV2Executor executor,
                            TenantScopes tenantScopes, ResultAssembler assembler, ReportRenderer renderer,
                            StagingArea staging, RunStore store, RunReader runs, ArtifactReader artifacts,
                            ArtifactDeliveries deliveries, ReportRunDocuments documents, Settings settings,
                            Clock clock, Supplier<UUID> ids) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.translator = Objects.requireNonNull(translator, "translator");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.tenantScopes = Objects.requireNonNull(tenantScopes, "tenantScopes");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.store = Objects.requireNonNull(store, "store");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.downloads = new Semaphore(settings.maxConcurrentDownloads(), true);
    }

    // ------------------------------------------------------------------ run

    public RunReport run(Requester requester, UUID dataSourceId, UUID reportPlanId) {
        RunOwner owner = owner(requester);
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(reportPlanId, "reportPlanId");
        UUID tenantId = requester.tenantId();
        // 2. a configured destination, without resolving its secret (B10-O3)
        SourceTarget target = availability.configured(tenantId, dataSourceId)
                .orElseThrow(() -> new ExecutionException(ErrorCode.DATA_SOURCE_NOT_CONFIGURED,
                        "no read-only connection configured for the data source"));
        if (!tenantId.equals(target.tenantId()) || !dataSourceId.equals(target.dataSourceId())) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_NOT_CONFIGURED, "destination of another source");
        }
        // 3. Semantic
        SemanticDataSource described = semantic.dataSource(tenantId, dataSourceId);
        if (!dataSourceId.equals(described.id())) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "data source id differs");
        }
        if (!described.executable()) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_NOT_EXECUTABLE, "data source not active PostgreSQL");
        }
        Documents fetched = fetch(tenantId, dataSourceId, reportPlanId);
        // 4. start
        UUID runId = ids.get();
        RunStart start = new RunStart(runId, tenantId, dataSourceId, reportPlanId, owner, settings.maxDuration());
        try {
            store.begin(start);
        } catch (RunStoreException e) {
            throw new ExecutionException(ErrorCode.RUN_STORAGE_UNAVAILABLE, "run could not be started", e);
        }
        RunRef ref = start.ref();
        FinishResult finished;
        try {
            finished = execute(ref, reportPlanId, target, described, fetched);
        } catch (RunStoreException e) {
            throw new RunException(ErrorCode.RUN_STORAGE_UNAVAILABLE, runId, "outcome could not be recorded", e);
        } catch (RuntimeException e) {
            // A4-B10 (D-B10-13): the message of an unexpected failure may carry values; it is withheld.
            LOG.warn("run {} failed internally", runId, RedactedFailure.of(e));
            finished = finishOrThrow(ref, RunOutcome.failed(FailureCode.INTERNAL_ERROR));
        }
        LOG.info("run {} recorded as {} {}", runId, finished.kind(), finished.code().orElse(""));
        return answer(new RunScope(tenantId, dataSourceId, owner.subject()), runId, true);
    }

    private record Documents(Map<String, Object> reportPlan, Map<String, Object> spec,
                             Map<UUID, Map<String, Object>> logicalPlans) {
    }

    /** Step 3: the documents, read before the run starts so that an unreachable Semantic leaves no run. */
    private Documents fetch(UUID tenantId, UUID dataSourceId, UUID reportPlanId) {
        Map<String, Object> reportPlan = semantic.reportPlan(tenantId, dataSourceId, reportPlanId);
        Map<String, Object> spec = semantic.spec(tenantId, dataSourceId, reportPlanId);
        Map<UUID, Map<String, Object>> plans = new LinkedHashMap<>();
        List<UUID> planIds = declaredPlanIds(reportPlan);
        if (planIds.size() <= settings.maxPlans()) {
            for (UUID planId : planIds) {
                plans.putIfAbsent(planId, semantic.logicalPlan(tenantId, dataSourceId, reportPlanId, planId));
            }
        }
        // A malformed or oversized list of plans is not fetched: the validation (step 5) refuses the report plan.
        return new Documents(reportPlan, spec, plans);
    }

    /** The plan ids the report plan declares, read defensively; anything unexpected yields what is readable. */
    private static List<UUID> declaredPlanIds(Map<String, Object> reportPlan) {
        if (!(reportPlan.get("plans") instanceof List<?> refs)) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Object ref : refs) {
            if (ref instanceof Map<?, ?> map && map.get("planId") instanceof String text) {
                try {
                    UUID id = UUID.fromString(text);
                    if (id.toString().equals(text)) {
                        ids.add(id);
                    }
                } catch (IllegalArgumentException ignored) {
                    // not a canonical UUID: refused by the schema in step 5
                }
            }
        }
        return ids;
    }

    /** Steps 5 to 11, after the run started. Every way out records exactly one outcome. */
    private FinishResult execute(RunRef ref, UUID reportPlanId, SourceTarget target,
                                 SemanticDataSource described, Documents docs) {
        UUID tenantId = ref.tenantId();
        UUID dataSourceId = ref.dataSourceId();
        // 5. independent validation
        ValidatedReportPlan plan;
        try {
            plan = validator.validate(tenantId, dataSourceId, docs.reportPlan(), docs.spec(), docs.logicalPlans());
        } catch (PlanV2Exception e) {
            LOG.info("run {} refused by validation: {}", ref.runId(), e.getMessage());
            String trusted = trustedFingerprint(docs.reportPlan());
            return store.finish(ref, RunOutcome.refused(refusalCode(e), trusted, null));
        }
        String planFingerprint = plan.reportPlan().fingerprint();
        String specFingerprint = plan.spec().fingerprint();
        SpecPresentation presentation;
        try {
            presentation = SpecPresentation.from(docs.spec(), plan, sha256);
        } catch (ResultAssemblyException e) {
            return store.finish(ref, RunOutcome.refused(e.code().refusalCode(), planFingerprint, specFingerprint));
        }
        // 6. validity before reading
        Optional<String> before = staleness(ref, reportPlanId, "plan_stale");
        if (before.isPresent()) {
            return store.finish(ref, RunOutcome.refused(before.get(), planFingerprint, specFingerprint));
        }
        // 7. translation
        List<SqlQueryV2> queries = plan.datasets().stream().map(translator::translate).toList();
        ExecutionRequestV2 request = ExecutionRequestV2.of(plan, queries);
        // 7 bis. the credentials, only now that the plan will be read (B10-O3)
        DataSourceCredentials source;
        try {
            source = credentials.resolve(tenantId, dataSourceId)
                    .orElseThrow(() -> new ExecutionException(ErrorCode.DATA_SOURCE_NOT_CONFIGURED,
                            "the configuration of the data source disappeared after the run started"));
        } catch (ExecutionException e) {
            LOG.info("run {} refused before reading: {}", ref.runId(), e.code());
            return store.finish(ref, RunOutcome.refused(refusalCode(e.code()), planFingerprint, specFingerprint));
        }
        if (!target.equals(source.target())) {
            LOG.info("run {} refused before reading: {}", ref.runId(), ErrorCode.DATA_SOURCE_CONFIGURATION_CHANGED);
            return store.finish(ref, RunOutcome.refused(refusalCode(ErrorCode.DATA_SOURCE_CONFIGURATION_CHANGED),
                    planFingerprint, specFingerprint));
        }
        // 8. one snapshot, with the tenant scope and schema checks
        Instant started = clock.instant();
        SnapshotRead read;
        try {
            read = executor.executeAll(source, request, RunBudget.of(plan.reportPlan().budget(), settings.limits(),
                    settings.maxEstimatedBytes()), tenantScopes.forExecution(request));
        } catch (ExecutionException e) {
            LOG.info("run {} refused while reading: {}", ref.runId(), e.code());
            return store.finish(ref, RunOutcome.refused(refusalCode(e.code()), planFingerprint, specFingerprint));
        }
        Instant readFinished = clock.instant();
        // 9. validity after reading
        Optional<String> after = staleness(ref, reportPlanId, "plan_changed_during_execution");
        if (after.isPresent()) {
            return store.finish(ref, RunOutcome.refused(after.get(), planFingerprint, specFingerprint));
        }
        // 10. assembly and presentation
        ReportResult result;
        try {
            result = assembler.assemble(new AssemblyInput(ref.runId(), plan, presentation, queries, read, started,
                    readFinished, clock.instant(), settings.reportsVersion(), described.name(), Optional.empty(),
                    settings.assembly()));
        } catch (ResultAssemblyException e) {
            return store.finish(ref, RunOutcome.refused(e.code().refusalCode(), planFingerprint, specFingerprint));
        }
        RunOutcome outcome = RunOutcome.of(result);
        if (source.transportException() != null) {
            // EX-TLS-1: recorded in the run (nexora-report-run/1 warnings), never in the Excel file.
            LOG.warn("run {} read data source {} under transport exception {}: the connection was NOT encrypted and"
                    + " the server identity was NOT verified", ref.runId(), dataSourceId,
                    source.transportException().id());
            outcome = outcome.withWarning(ResultWarning.transportNotVerified());
        }
        try (StagingArea.Staged file = staging.create()) {
            RenderedArtifact artifact;
            try (OutputStream out = file.openOutput()) {
                artifact = renderer.render(result, out, settings.render());
            } catch (PresentationException e) {
                LOG.info("run {} not presented: {}", ref.runId(), e.code());
                return store.finish(ref, e.code() == PresentationException.Code.RENDER_FAILED
                        ? outcome.withFailure(FailureCode.RENDER_FAILED) : outcome.withRefusal(e.code().refusalCode()));
            }
            // 11. outcome, artifact and content in one transaction (quota and size enforced by B8)
            return store.finishWithArtifact(ref, outcome, artifact, file.path());
        } catch (IOException e) {
            LOG.warn("run {}: staging failed", ref.runId(), e);
            return store.finish(ref, outcome.withFailure(FailureCode.STORAGE_FAILED));
        }
    }

    /** Empty when the plan is CURRENT; otherwise the refusal code (validity that cannot be confirmed is refused). */
    private Optional<String> staleness(RunRef ref, UUID reportPlanId, String staleCode) {
        ReportPlanValidity validity;
        try {
            validity = semantic.validity(ref.tenantId(), ref.dataSourceId(), reportPlanId);
        } catch (ExecutionException e) {
            return Optional.of(refusalCode(e.code() == ErrorCode.SEMANTIC_UNAVAILABLE
                    || e.code() == ErrorCode.SEMANTIC_CONTRACT_VIOLATION ? ErrorCode.PLAN_VALIDITY_UNCONFIRMED
                    : e.code()));
        }
        if (validity.current()) {
            return Optional.empty();
        }
        LOG.info("run {}: report plan not current {}", ref.runId(), validity.issueCodes());
        return Optional.of(staleCode);
    }

    /** Records an outcome after an internal failure; if even that is impossible, the reconciliation closes the run. */
    private FinishResult finishOrThrow(RunRef ref, RunOutcome outcome) {
        try {
            return store.finish(ref, outcome);
        } catch (RunStoreException e) {
            throw new RunException(ErrorCode.RUN_STORAGE_UNAVAILABLE, ref.runId(), "outcome could not be recorded",
                    e);
        }
    }

    // ------------------------------------------------------------------ reads

    /** The {@code nexora-report-run/1} of a run of the requester, as recorded. */
    public RunReport find(Requester requester, UUID dataSourceId, UUID runId) {
        RunOwner owner = owner(requester);
        return answer(new RunScope(requester.tenantId(), Objects.requireNonNull(dataSourceId, "dataSourceId"),
                owner.subject()), Objects.requireNonNull(runId, "runId"), false);
    }

    private RunReport answer(RunScope scope, UUID runId, boolean justRun) {
        RunView view;
        try {
            view = runs.find(scope, runId).orElseThrow(() -> new ExecutionException(ErrorCode.RUN_NOT_FOUND,
                    "no such run for the requester"));
        } catch (RunStoreException e) {
            throw new RunException(ErrorCode.RUN_STORAGE_UNAVAILABLE, runId, "run not readable", e);
        }
        switch (view.state()) {
            case IN_PROGRESS -> throw new RunException(ErrorCode.RUN_IN_PROGRESS, runId, "run in progress");
            case FAILED -> throw new RunException(justRun ? ErrorCode.RUN_FAILED : ErrorCode.RUN_RECORDED_AS_FAILED,
                    runId, "run failed");
            case INTERRUPTED -> throw new RunException(ErrorCode.RUN_INTERRUPTED, runId, "run interrupted");
            default -> {
                // COMPLETED, PARTIAL, REFUSED
            }
        }
        Map<String, Object> document = documents.reportRun(view).orElseThrow(() -> new RunException(
                ErrorCode.REPORT_PLAN_INVALID, runId, "refused without a known report plan fingerprint"));
        return new RunReport(runId, document);
    }

    /**
     * Writes the artifact of a run of the requester to {@code sink} and records whether it was delivered. At most
     * {@link Settings#maxConcurrentDownloads()} at a time ({@code TOO_MANY_DOWNLOADS}).
     */
    public void download(Requester requester, UUID dataSourceId, UUID runId, ArtifactSink sink) throws IOException {
        RunOwner owner = owner(requester);
        Objects.requireNonNull(sink, "sink");
        RunScope scope = new RunScope(requester.tenantId(), Objects.requireNonNull(dataSourceId, "dataSourceId"),
                owner.subject());
        if (!downloads.tryAcquire()) {
            throw new ExecutionException(ErrorCode.TOO_MANY_DOWNLOADS, "no download slot");
        }
        try {
            Retrieval retrieval;
            try {
                retrieval = artifacts.open(scope, Objects.requireNonNull(runId, "runId"));
            } catch (RunStoreException e) {
                throw new RunException(ErrorCode.RUN_STORAGE_UNAVAILABLE, runId, "artifact not readable", e);
            }
            switch (retrieval) {
                case Retrieval.Available available -> deliver(available, sink);
                case Retrieval.Gone gone -> throw new RunException(ErrorCode.ARTIFACT_GONE, runId,
                        "artifact " + gone.state());
                case Retrieval.IntegrityFailed failed -> throw new RunException(ErrorCode.ARTIFACT_INTEGRITY_FAILED,
                        runId, "stored artifact does not match its metadata");
                case Retrieval.NotFound notFound -> throw new ExecutionException(ErrorCode.RUN_NOT_FOUND,
                        "no artifact for the requester");
            }
        } finally {
            downloads.release();
        }
    }

    private void deliver(Retrieval.Available available, ArtifactSink sink) throws IOException {
        StoredArtifact artifact = available.artifact();
        try {
            sink.write(artifact, available.content());
        } catch (IOException e) {
            recordQuietly(() -> deliveries.deliveryFailed(artifact, "client_write_failed"), artifact);
            throw e;
        }
        recordQuietly(() -> deliveries.delivered(artifact), artifact);
    }

    /** The bytes are already with the client; a failure to record it is logged, never turned into an error. */
    private static void recordQuietly(Runnable record, StoredArtifact artifact) {
        try {
            record.run();
        } catch (RunStoreException e) {
            LOG.warn("delivery of artifact {} not recorded", artifact.artifactId(), e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Step 1: a person or a service of the token's tenant; an AI principal never runs or reads a run. */
    private static RunOwner owner(Requester requester) {
        Objects.requireNonNull(requester, "requester");
        return switch (requester.type()) {
            case HUMAN -> new RunOwner(requester.subject(), RunOwner.Type.HUMAN);
            case SERVICE -> new RunOwner(requester.subject(), RunOwner.Type.SERVICE);
            case AI -> throw new ExecutionException(ErrorCode.REQUESTER_NOT_ALLOWED, "AI principals run no reports");
        };
    }

    /**
     * The fingerprint of a report plan that failed validation, only if the document carries a well-formed one that
     * equals its recomputed fingerprint; otherwise unknown ({@code null}).
     */
    private String trustedFingerprint(Map<String, Object> reportPlan) {
        try {
            Object declared = reportPlan.get(CanonicalJson.FINGERPRINT_FIELD);
            if (declared instanceof String text && FINGERPRINT.matcher(text).matches()
                    && text.equals(CanonicalJson.fingerprintOf(reportPlan, sha256))) {
                return text;
            }
        } catch (RuntimeException e) {
            // an unusual tree: the fingerprint stays unknown
        }
        return null;
    }

    /** The refusal of a validation failure: the coarse kind of its first violation ({@code contract_invalid}...). */
    static String refusalCode(PlanV2Exception e) {
        return e.first().kind().name().toLowerCase(Locale.ROOT);
    }

    /** The refusal of an execution failure: its stable code in lower case ({@code tenant_scope_unverified}...). */
    static String refusalCode(ErrorCode code) {
        return code.name().toLowerCase(Locale.ROOT);
    }
}
