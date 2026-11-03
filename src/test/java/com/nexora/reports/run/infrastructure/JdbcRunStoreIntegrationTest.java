package com.nexora.reports.run.infrastructure;

import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.render.xlsx.XlsxRenderer;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.run.domain.ArtifactState;
import com.nexora.reports.run.domain.FailureCode;
import com.nexora.reports.run.domain.FinishResult;
import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunOwner;
import com.nexora.reports.run.domain.RunRef;
import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunStart;
import com.nexora.reports.run.domain.RunState;
import com.nexora.reports.run.domain.RunStoreException;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.support.RunRegistryDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B8 with PostgreSQL 17.6: the run registry migrated by Flyway (V1 to V3) and used through the restricted
 * application role. It covers the additional condition of «PROCEDE A4-B8» (a started run stays; a storage error
 * leaves nothing partial; every real outcome; quota as a refusal; reconciliation without duplicates), the integrity
 * checks of the database (adjustment 2), retrieval without confirming deliveries (adjustment 3), the negative
 * cross-access tests (adjustment 4), the life cycle and concurrency.
 */
class JdbcRunStoreIntegrationTest {

    private static final RunOwner ANA = new RunOwner("ana", RunOwner.Type.HUMAN);
    private static RunRegistryDatabase db;
    private static DataSource app;
    private static Path dir;
    private static Rendered paA;
    private static Rendered partial;

    private record Rendered(ReportResult result, RenderedArtifact artifact, Path file) {
    }

    @BeforeAll
    static void setUp() throws IOException {
        db = RunRegistryDatabase.create("run_registry");
        app = db.app();
        dir = Files.createTempDirectory("b8-it");
        paA = render(RenderFixtures.paA(), "pa-a.xlsx");
        partial = render(RenderFixtures.truncatedPaA(), "partial.xlsx");
    }

    @AfterAll
    static void tearDown() throws IOException {
        ArtifactStagingTest.delete(dir);
    }

    private static Rendered render(ReportResult result, String name) throws IOException {
        Path file = dir.resolve(name);
        RenderedArtifact artifact;
        try (OutputStream out = Files.newOutputStream(file)) {
            artifact = new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
        }
        return new Rendered(result, artifact, file);
    }

    private static JdbcRunStore store(StorageLimits limits) {
        return new JdbcRunStore(app, limits);
    }

    private static RunStart start(UUID tenant, Duration maxDuration) {
        return new RunStart(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(), ANA, maxDuration);
    }

    private static RunStart started(JdbcRunStore store, UUID tenant) {
        RunStart start = start(tenant, Duration.ofMinutes(5));
        store.begin(start);
        return start;
    }

    private static RunScope scope(RunStart s) {
        return new RunScope(s.tenantId(), s.dataSourceId(), s.owner().subject());
    }

    // ------------------------------------------------------------------ outcomes

    @Test
    void aCompletedRunIsStoredAndHandedBackByteForByte() throws IOException, SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());

        FinishResult r = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());

        assertThat(r.kind()).isEqualTo(RunOutcomeKind.COMPLETED);
        assertThat(r.recordedNow()).isTrue();
        assertThat(r.artifact().orElseThrow().sha256()).isEqualTo(paA.artifact().sha256());
        assertThat(r.artifact().orElseThrow().sheets()).isEqualTo(paA.artifact().sheets());
        assertThat(r.artifact().orElseThrow().expiresAt())
                .isEqualTo(r.artifact().orElseThrow().createdAt().plus(Duration.ofDays(7)));
        Retrieval got = reader().open(scope(s), s.runId());
        assertThat(got).isInstanceOf(Retrieval.Available.class);
        byte[] bytes = ((Retrieval.Available) got).content();
        assertThat(java.util.Arrays.equals(bytes, Files.readAllBytes(paA.file()))).isTrue();
        assertThat(sha256(bytes)).isEqualTo(paA.artifact().sha256());
        assertThat(events(s.runId())).containsExactly("RUN_STARTED", "RUN_FINISHED:COMPLETED", "ARTIFACT_STORED",
                "ARTIFACT_RETRIEVED");
    }

    @Test
    void theRunIsReadBackWithItsDatasetsWarningsAndState() {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        assertThat(reader().find(scope(s), s.runId()).orElseThrow().state()).isEqualTo(RunState.IN_PROGRESS);

        RunOutcome outcome = RunOutcome.of(partial.result());
        store.finishWithArtifact(s.ref(), outcome, partial.artifact(), partial.file());

        RunView view = reader().find(scope(s), s.runId()).orElseThrow();
        assertThat(view.state()).isEqualTo(RunState.PARTIAL);
        assertThat(view.outcome().orElseThrow()).isEqualTo(outcome);
        assertThat(view.owner()).isEqualTo(ANA);
        assertThat(view.reportPlanId()).isEqualTo(s.reportPlanId());
        assertThat(view.artifactState()).contains(ArtifactState.AVAILABLE);
        assertThat(view.deadlineAt()).isEqualTo(view.startedAt().plus(Duration.ofMinutes(5)));
    }

    @Test
    void refusedAndFailedRunsHaveNoArtifact() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart refused = started(store, UUID.randomUUID());
        RunStart failed = started(store, UUID.randomUUID());

        FinishResult r = store.finish(refused.ref(), RunOutcome.refused("tenant_scope_unverified", null, null));
        FinishResult f = store.finish(failed.ref(), RunOutcome.failed(FailureCode.RENDER_FAILED));

        assertThat(r.kind()).isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(r.code()).contains("tenant_scope_unverified");
        assertThat(f.kind()).isEqualTo(RunOutcomeKind.FAILED);
        assertThat(f.code()).contains("render_failed");
        assertThat(count("report_artifact", refused.runId())).isEqualTo(0L);
        assertThat(count("report_artifact", failed.runId())).isEqualTo(0L);
        assertThat(reader().open(scope(refused), refused.runId())).isEqualTo(Retrieval.NOT_FOUND);
        assertThat(reader().find(scope(failed), failed.runId()).orElseThrow().state()).isEqualTo(RunState.FAILED);
        assertThatThrownBy(() -> store.finish(refused.ref(), RunOutcome.of(paA.result())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.finish(refused.ref(), RunOutcome.interrupted()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRunGetsOneOutcomeOnly() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        store.finish(s.ref(), RunOutcome.refused("physical_schema_mismatch", null, null));

        FinishResult again = store.finish(s.ref(), RunOutcome.failed(FailureCode.INTERNAL_ERROR));
        FinishResult late = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(),
                paA.file());

        assertThat(again.recordedNow()).isFalse();
        assertThat(again.kind()).isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(again.code()).contains("physical_schema_mismatch");
        assertThat(late.recordedNow()).isFalse();
        assertThat(late.artifact()).isEmpty();
        assertThat(count("report_artifact", s.runId())).isEqualTo(0L);
        assertThat(count("report_run_outcome", s.runId())).isEqualTo(1L);
    }

    @Test
    void unknownRunsAndRepeatedStartsAreRefused() {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        Throwable twice = catchThrowable(() -> store.begin(s));
        assertThat(((RunStoreException) twice).code()).isEqualTo(RunStoreException.Code.ALREADY_STARTED);

        RunRef unknown = new RunRef(UUID.randomUUID(), s.tenantId(), s.dataSourceId());
        Throwable t = catchThrowable(() -> store.finish(unknown, RunOutcome.failed(FailureCode.INTERNAL_ERROR)));
        assertThat(((RunStoreException) t).code()).isEqualTo(RunStoreException.Code.UNKNOWN_RUN);
        RunRef otherTenant = new RunRef(s.runId(), UUID.randomUUID(), s.dataSourceId());
        Throwable u = catchThrowable(() -> store.finishWithArtifact(otherTenant, RunOutcome.of(paA.result()),
                paA.artifact(), paA.file()));
        assertThat(((RunStoreException) u).code()).isEqualTo(RunStoreException.Code.UNKNOWN_RUN);
        RunRef otherSource = new RunRef(s.runId(), s.tenantId(), UUID.randomUUID());
        Throwable v = catchThrowable(() -> store.finish(otherSource, RunOutcome.failed(FailureCode.INTERNAL_ERROR)));
        assertThat(((RunStoreException) v).code()).isEqualTo(RunStoreException.Code.UNKNOWN_RUN);
    }

    // ------------------------------------------------------------------ nothing partial

    @Test
    void overTheQuotaTheRunIsRefusedAndNothingOfTheArtifactRemains() throws SQLException {
        long size = paA.artifact().sizeBytes();
        JdbcRunStore store = store(new StorageLimits(Duration.ofDays(7), size + size / 2, size));
        UUID tenant = UUID.randomUUID();
        RunStart first = started(store, tenant);
        RunStart second = started(store, tenant);
        RunStart otherTenant = started(store, UUID.randomUUID());

        assertThat(store.finishWithArtifact(first.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file())
                .kind()).isEqualTo(RunOutcomeKind.COMPLETED);
        FinishResult refused = store.finishWithArtifact(second.ref(), RunOutcome.of(paA.result()), paA.artifact(),
                paA.file());
        FinishResult other = store.finishWithArtifact(otherTenant.ref(), RunOutcome.of(paA.result()),
                paA.artifact(), paA.file());

        assertThat(refused.kind()).isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(refused.code()).contains("artifact_quota_exceeded");
        assertThat(refused.artifact()).isEmpty();
        assertThat(count("report_artifact", second.runId())).isEqualTo(0L);
        assertThat(events(second.runId())).containsExactly("RUN_STARTED", "RUN_FINISHED:REFUSED");
        RunOutcome kept = reader().find(scope(second), second.runId()).orElseThrow().outcome().orElseThrow();
        assertThat(kept.reportPlanFingerprint()).isEqualTo(paA.result().run().reportPlanFingerprint());
        assertThat(kept.datasets()).hasSize(3);
        assertThat(other.kind()).isEqualTo(RunOutcomeKind.COMPLETED);
    }

    @Test
    void aFileOverTheConfiguredSizeIsRefused() throws SQLException {
        long size = paA.artifact().sizeBytes();
        JdbcRunStore store = store(new StorageLimits(Duration.ofDays(7), size, size - 1));
        RunStart s = started(store, UUID.randomUUID());
        FinishResult r = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());
        assertThat(r.kind()).isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(r.code()).contains("artifact_too_large");
        assertThat(count("report_artifact", s.runId())).isEqualTo(0L);
    }

    @Test
    void aFileThatDiffersFromItsDescriptionFailsAndLeavesNothing() throws SQLException, IOException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RenderedArtifact a = paA.artifact();
        RenderedArtifact wrongSha = new RenderedArtifact(a.mediaType(), a.excelContract(), a.sheets(), a.sizeBytes(),
                "0".repeat(64));
        RenderedArtifact wrongSize = new RenderedArtifact(a.mediaType(), a.excelContract(), a.sheets(),
                a.sizeBytes() + 1, a.sha256());
        Path missing = dir.resolve("missing.xlsx");
        for (Object[] attempt : new Object[][] {{wrongSha, paA.file()}, {wrongSize, paA.file()}, {a, missing}}) {
            RunStart s = started(store, UUID.randomUUID());
            FinishResult r = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()),
                    (RenderedArtifact) attempt[0], (Path) attempt[1]);
            assertThat(r.kind()).isEqualTo(RunOutcomeKind.FAILED);
            assertThat(r.code()).contains("storage_failed");
            assertThat(count("report_artifact", s.runId())).isEqualTo(0L);
            assertThat(events(s.runId())).containsExactly("RUN_STARTED", "RUN_FINISHED:FAILED");
            assertThat(reader().find(scope(s), s.runId()).orElseThrow().outcome().orElseThrow().datasets())
                    .hasSize(3);
        }
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void aDatabaseErrorWhileStoringTheContentLeavesNothingPartial() throws SQLException {
        JdbcRunStore store = new JdbcRunStore(failingOn(RunSql.INSERT_CONTENT), StorageLimits.DEFAULTS);
        RunStart s = started(store(StorageLimits.DEFAULTS), UUID.randomUUID());

        FinishResult r = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());

        assertThat(r.kind()).isEqualTo(RunOutcomeKind.FAILED);
        assertThat(r.code()).contains("storage_failed");
        assertThat(count("report_artifact", s.runId())).isEqualTo(0L);
        assertThat(countContentOfRun(s.runId())).isEqualTo(0L);
        assertThat(count("report_run_outcome", s.runId())).isEqualTo(1L);
        assertThat(events(s.runId())).containsExactly("RUN_STARTED", "RUN_FINISHED:FAILED");
    }

    @Test
    void whenNothingCanBeRecordedTheStartRemainsForTheReconciliation() throws SQLException {
        RunStart s = started(store(StorageLimits.DEFAULTS), UUID.randomUUID());
        JdbcRunStore broken = new JdbcRunStore(failingOn(RunSql.INSERT_OUTCOME), StorageLimits.DEFAULTS);

        Throwable t = catchThrowable(() -> broken.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()),
                paA.artifact(), paA.file()));

        assertThat(((RunStoreException) t).code()).isEqualTo(RunStoreException.Code.STORAGE_UNAVAILABLE);
        assertThat(count("report_run", s.runId())).isEqualTo(1L);
        assertThat(count("report_run_outcome", s.runId())).isEqualTo(0L);
        assertThat(count("report_artifact", s.runId())).isEqualTo(0L);
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    void anAbandonedRunIsInterruptedOnceAndALateFinishStoresNothing() throws InterruptedException, SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = start(UUID.randomUUID(), Duration.ofSeconds(1));
        store.begin(s);
        RunStart alive = started(store, s.tenantId());
        Thread.sleep(1500);
        assertThat(reader().find(scope(s), s.runId()).orElseThrow().state()).isEqualTo(RunState.INTERRUPTED);

        JdbcRunMaintenance maintenance = new JdbcRunMaintenance(app);
        int closed = 0;
        for (int i = 0; i < 20 && count("report_run_outcome", s.runId()) == 0; i++) {
            closed += maintenance.closeAbandoned(1000);
        }
        FinishResult late = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(),
                paA.file());

        assertThat(closed >= 1).isTrue();
        assertThat(count("report_run_outcome", s.runId())).isEqualTo(1L);
        assertThat(late.recordedNow()).isFalse();
        assertThat(late.kind()).isEqualTo(RunOutcomeKind.INTERRUPTED);
        assertThat(count("report_artifact", s.runId())).isEqualTo(0L);
        assertThat(events(s.runId())).containsExactly("RUN_STARTED", "RUN_INTERRUPTED", "RUN_FINISHED:INTERRUPTED");
        assertThat(reader().find(scope(alive), alive.runId()).orElseThrow().state()).isEqualTo(RunState.IN_PROGRESS);
        assertThat(count("report_run_outcome", alive.runId())).isEqualTo(0L);
    }

    @Test
    void reconciliationAndALateFinishRacingProduceExactlyOneOutcome() throws Exception {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        JdbcRunMaintenance maintenance = new JdbcRunMaintenance(app);
        List<RunStart> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RunStart s = start(UUID.randomUUID(), Duration.ofSeconds(1));
            store.begin(s);
            runs.add(s);
        }
        Thread.sleep(1500);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<FinishResult>> finishes = new ArrayList<>();
            for (RunStart s : runs) {
                finishes.add(pool.submit(() -> {
                    go.await();
                    return store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(),
                            paA.file());
                }));
            }
            Future<Integer> reconcile = pool.submit(() -> {
                go.await();
                return maintenance.closeAbandoned(1000);
            });
            go.countDown();
            reconcile.get();
            for (int i = 0; i < runs.size(); i++) {
                FinishResult r = finishes.get(i).get();
                long outcomes = count("report_run_outcome", runs.get(i).runId());
                long artifacts = count("report_artifact", runs.get(i).runId());
                assertThat(outcomes).isEqualTo(1L);
                assertThat(artifacts).isEqualTo(r.recordedNow() ? 1L : 0L);
                assertThat(r.kind()).isEqualTo(r.recordedNow() ? RunOutcomeKind.COMPLETED
                        : RunOutcomeKind.INTERRUPTED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ concurrency and quota

    @Test
    void concurrentRunsAtTheEdgeOfTheQuotaStoreExactlyOne() throws Exception {
        long size = paA.artifact().sizeBytes();
        JdbcRunStore store = store(new StorageLimits(Duration.ofDays(7), size + size / 2, size));
        UUID tenant = UUID.randomUUID();
        List<RunStart> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            runs.add(started(store, tenant));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<FinishResult>> results = new ArrayList<>();
            for (RunStart s : runs) {
                Callable<FinishResult> c = () -> {
                    go.await();
                    return store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(),
                            paA.file());
                };
                results.add(pool.submit(c));
            }
            go.countDown();
            int completed = 0;
            int refused = 0;
            for (Future<FinishResult> f : results) {
                FinishResult r = f.get();
                if (r.kind() == RunOutcomeKind.COMPLETED) {
                    completed++;
                } else {
                    assertThat(r.code()).contains("artifact_quota_exceeded");
                    refused++;
                }
            }
            assertThat(completed).isEqualTo(1);
            assertThat(refused).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ retrieval and isolation (adjustment 4)

    @Test
    void anyOtherTenantSourceOrOwnerFindsNothing() {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());
        JdbcRunReader reader = reader();

        List<RunScope> outsiders = List.of(
                new RunScope(UUID.randomUUID(), s.dataSourceId(), "ana"),
                new RunScope(s.tenantId(), UUID.randomUUID(), "ana"),
                new RunScope(s.tenantId(), s.dataSourceId(), "luis"),
                new RunScope(s.tenantId(), s.dataSourceId(), "ANA"));
        for (RunScope outsider : outsiders) {
            assertThat(reader.open(outsider, s.runId())).isEqualTo(Retrieval.NOT_FOUND);
            assertThat(reader.find(outsider, s.runId())).isEmpty();
        }
        assertThat(reader.open(scope(s), UUID.randomUUID())).isEqualTo(Retrieval.NOT_FOUND);
        assertThat(reader.find(scope(s), UUID.randomUUID())).isEmpty();
        assertThat(reader.open(scope(s), s.runId())).isInstanceOf(Retrieval.Available.class);
    }

    @Test
    void theApplicationRoleCannotReadAcrossTenantsThroughTheReaderEvenWithAKnownArtifactId() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart a = started(store, UUID.randomUUID());
        RunStart b = started(store, UUID.randomUUID());
        store.finishWithArtifact(a.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());
        store.finishWithArtifact(b.ref(), RunOutcome.of(partial.result()), partial.artifact(), partial.file());

        RunScope bAsA = new RunScope(a.tenantId(), b.dataSourceId(), "ana");
        assertThat(reader().open(bAsA, b.runId())).isEqualTo(Retrieval.NOT_FOUND);
        Retrieval.Available own = (Retrieval.Available) reader().open(scope(b), b.runId());
        assertThat(own.artifact().tenantId()).isEqualTo(b.tenantId());
        assertThat(sha256(own.content())).isEqualTo(partial.artifact().sha256());
    }

    @Test
    void retrievingRecordsAHandOverNeverADelivery() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());
        reader().open(scope(s), s.runId());
        reader().open(scope(s), s.runId());

        assertThat(events(s.runId())).containsExactly("RUN_STARTED", "RUN_FINISHED:COMPLETED", "ARTIFACT_STORED",
                "ARTIFACT_RETRIEVED", "ARTIFACT_RETRIEVED");
        try (Connection c = db.appConnection(); Statement st = c.createStatement()) {
            Throwable t = catchThrowable(() -> st.executeUpdate("INSERT INTO report_run_event (tenant_id, run_id, kind)"
                    + " VALUES ('" + s.tenantId() + "', '" + s.runId() + "', 'ARTIFACT_DOWNLOADED')"));
            assertThat(t).isInstanceOf(SQLException.class);
        }
    }

    // ------------------------------------------------------------------ life cycle

    @Test
    void anExpiredArtifactIsGoneBeforeThePurgeAndPurgedAfterIt() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart current = started(store, UUID.randomUUID());
        store.finishWithArtifact(current.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());
        RunScope expired = insertExpired();

        UUID expiredRun = runOf(expired);
        Retrieval before = reader().open(expired, expiredRun);
        assertThat(((Retrieval.Gone) before).state()).isEqualTo(ArtifactState.EXPIRED);

        JdbcRunMaintenance maintenance = new JdbcRunMaintenance(app);
        int purged = 0;
        for (int i = 0; i < 50 && countContentOfRun(expiredRun) > 0; i++) {
            purged += maintenance.purgeExpired(1000);
        }
        assertThat(purged >= 1).isTrue();
        assertThat(((Retrieval.Gone) reader().open(expired, expiredRun)).state()).isEqualTo(ArtifactState.PURGED);
        assertThat(reader().find(expired, expiredRun).orElseThrow().artifactState()).contains(ArtifactState.PURGED);
        assertThat(events(expiredRun)).contains("ARTIFACT_PURGED");
        assertThat(reader().open(scope(current), current.runId())).isInstanceOf(Retrieval.Available.class);
        assertThat(count("report_artifact", expiredRun)).isEqualTo(1L);
    }

    @Test
    void corruptedContentIsNeverHandedOver() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        UUID artifactId = store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file())
                .artifact().orElseThrow().artifactId();
        try (Connection c = db.superuser(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE report_artifact_content DISABLE TRIGGER report_artifact_content_no_update");
            try (PreparedStatement ps = c.prepareStatement("UPDATE report_artifact_content"
                    + " SET content = set_byte(content, 100, (get_byte(content, 100) + 1) % 256)"
                    + " WHERE artifact_id = ?")) {
                ps.setObject(1, artifactId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            } finally {
                st.execute("ALTER TABLE report_artifact_content ENABLE TRIGGER report_artifact_content_no_update");
            }
        }
        Retrieval r = reader().open(scope(s), s.runId());
        assertThat(r).isInstanceOf(Retrieval.IntegrityFailed.class);
        assertThat(events(s.runId())).contains("ARTIFACT_INTEGRITY_FAILED");
        assertThat(events(s.runId()).contains("ARTIFACT_RETRIEVED")).isFalse();
    }

    // ------------------------------------------------------------------ the database itself (adjustment 2, D-B8-2)

    @Test
    void theDatabaseRefusesContentThatDoesNotMatchItsArtifact() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        try (Connection c = db.appConnection()) {
            c.setAutoCommit(false);
            insertOutcomeAndArtifact(c, s, "\\x0102", 2);
            Throwable wrongSize = catchThrowable(() -> insertContent(c, s, "\\x010203"));
            assertThat(wrongSize).isInstanceOf(SQLException.class);
            c.rollback();
            insertOutcomeAndArtifact(c, s, "\\x0102", 2);
            Throwable wrongSha = catchThrowable(() -> insertContent(c, s, "\\x0103"));
            assertThat(wrongSha).isInstanceOf(SQLException.class);
            c.rollback();
            insertOutcomeAndArtifact(c, s, "\\x0102", 2);
            Throwable noContent = catchThrowable(c::commit);
            assertThat(noContent).isInstanceOf(SQLException.class);
            c.rollback();
        }
        assertThat(count("report_run_outcome", s.runId())).isEqualTo(0L);
    }

    @Test
    void theApplicationRoleCanNeitherChangeNorRemoveRecords() throws SQLException {
        JdbcRunStore store = store(StorageLimits.DEFAULTS);
        RunStart s = started(store, UUID.randomUUID());
        store.finishWithArtifact(s.ref(), RunOutcome.of(paA.result()), paA.artifact(), paA.file());
        List<String> statements = List.of(
                "UPDATE report_run SET owner_subject = 'eve' WHERE id = '" + s.runId() + "'",
                "DELETE FROM report_run WHERE id = '" + s.runId() + "'",
                "UPDATE report_run_outcome SET kind = 'FAILED' WHERE run_id = '" + s.runId() + "'",
                "DELETE FROM report_run_outcome WHERE run_id = '" + s.runId() + "'",
                "UPDATE report_artifact SET expires_at = now() WHERE run_id = '" + s.runId() + "'",
                "DELETE FROM report_artifact WHERE run_id = '" + s.runId() + "'",
                "UPDATE report_artifact_content SET content = '\\x00'",
                "DELETE FROM report_artifact_content WHERE tenant_id = '" + s.tenantId() + "'",
                "DELETE FROM report_run_event WHERE run_id = '" + s.runId() + "'",
                "TRUNCATE report_artifact_content");
        try (Connection c = db.appConnection(); Statement st = c.createStatement()) {
            for (String sql : statements) {
                assertThat(catchThrowable(() -> st.executeUpdate(sql))).as(sql).isInstanceOf(SQLException.class);
            }
        }
        try (Connection c = db.superuser(); Statement st = c.createStatement()) {
            for (String sql : List.of(statements.get(0), statements.get(2), statements.get(5), statements.get(7))) {
                assertThat(catchThrowable(() -> st.executeUpdate(sql))).as("superuser: " + sql)
                        .isInstanceOf(SQLException.class);
            }
        }
        assertThat(reader().open(scope(s), s.runId())).isInstanceOf(Retrieval.Available.class);
    }

    // ------------------------------------------------------------------ helpers

    private static JdbcRunReader reader() {
        return new JdbcRunReader(app, StorageLimits.DEFAULTS);
    }

    private static long count(String table, UUID runId) throws SQLException {
        String column = table.equals("report_run") ? "id" : "run_id";
        try (Connection c = db.superuser();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countContentOfRun(UUID runId) throws SQLException {
        try (Connection c = db.superuser(); PreparedStatement ps = c.prepareStatement("SELECT count(*)"
                + " FROM report_artifact_content c JOIN report_artifact a ON a.id = c.artifact_id WHERE a.run_id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> events(UUID runId) throws SQLException {
        List<String> events = new ArrayList<>();
        try (Connection c = db.superuser(); PreparedStatement ps = c.prepareStatement("SELECT kind, detail"
                + " FROM report_run_event WHERE run_id = ? ORDER BY recorded_at, kind")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(rs.getString(2) == null ? rs.getString(1) : rs.getString(1) + ":" + rs.getString(2));
                }
            }
        }
        return events;
    }

    /** Test-only (superuser): a complete run whose artifact expired two days ago. */
    private static RunScope insertExpired() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        try (Connection c = db.superuser(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("INSERT INTO report_run VALUES ('" + run + "', '" + tenant + "', '" + source + "', '"
                    + UUID.randomUUID() + "', 'ana', 'HUMAN', now() - interval '10 days',"
                    + " now() - interval '10 days' + interval '5 minutes')");
            st.execute("INSERT INTO report_run_outcome (run_id, tenant_id, data_source_id, kind,"
                    + " report_plan_fingerprint, spec_fingerprint, snapshot, datasets, warnings) VALUES ('" + run
                    + "', '" + tenant + "', '" + source + "', 'COMPLETED', repeat('a', 64), repeat('b', 64), '1:1:',"
                    + " '[]', '[]')");
            st.execute("INSERT INTO report_artifact VALUES ('" + artifact + "', '" + run + "', '" + tenant + "', '"
                    + source + "', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',"
                    + " 'nexora-excel-report/1', encode(sha256('\\x0102'::bytea), 'hex'), 2,"
                    + " ARRAY['Reporte', 'Metodología'], now() - interval '9 days', now() - interval '2 days')");
            st.execute("INSERT INTO report_artifact_content VALUES ('" + artifact + "', '" + tenant + "', '\\x0102')");
            c.commit();
        }
        expiredRuns.put(tenant, run);
        return new RunScope(tenant, source, "ana");
    }

    private static final java.util.Map<UUID, UUID> expiredRuns = new java.util.concurrent.ConcurrentHashMap<>();

    private static UUID runOf(RunScope scope) {
        return expiredRuns.get(scope.tenantId());
    }

    private static void insertOutcomeAndArtifact(Connection c, RunStart s, String content, long size)
            throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO report_run_outcome (run_id, tenant_id, data_source_id, kind,"
                    + " report_plan_fingerprint, spec_fingerprint, snapshot, datasets, warnings) VALUES ('"
                    + s.runId() + "', '" + s.tenantId() + "', '" + s.dataSourceId() + "', 'COMPLETED',"
                    + " repeat('a', 64), repeat('b', 64), '1:1:', '[]', '[]')");
            st.execute("INSERT INTO report_artifact VALUES ('" + artifactIdOf(s) + "', '" + s.runId() + "', '"
                    + s.tenantId() + "', '" + s.dataSourceId() + "',"
                    + " 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'nexora-excel-report/1',"
                    + " encode(sha256('" + content + "'::bytea), 'hex'), " + size + ", ARRAY['Reporte', 'Metodología'],"
                    + " now(), now() + interval '1 hour')");
        }
    }

    private static void insertContent(Connection c, RunStart s, String content) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO report_artifact_content VALUES ('" + artifactIdOf(s) + "', '" + s.tenantId()
                    + "', '" + content + "')");
        }
    }

    private static UUID artifactIdOf(RunStart s) {
        return new UUID(s.runId().getMostSignificantBits(), ~s.runId().getLeastSignificantBits());
    }

    /** The application data source, failing when a statement equal to {@code sql} is prepared. */
    private static DataSource failingOn(String sql) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection real = app.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args != null && sql.equals(args[0])) {
                                throw new SQLException("simulated failure", "08006");
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                return getConnection();
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
