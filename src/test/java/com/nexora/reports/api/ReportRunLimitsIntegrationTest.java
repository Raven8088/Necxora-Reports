package com.nexora.reports.api;

import com.nexora.reports.config.ExecutionProperties;
import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.TenantScopes;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.infrastructure.ExecutorTestAccess;
import com.nexora.reports.execution.infrastructure.PostgresReadOnlyExecutor;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.PlanV2Validator;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.support.RunApiSetup;
import com.nexora.reports.support.TenantDatabase;
import com.nexora.reports.support.TlsBench;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.support.RunApiSetup.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4-B10-b, test T-8 of the audit (R4-7): the limits of a {@code /2} run, end to end through the API, each one ending
 * in a REFUSED run with its code and no row exposed (no artifact, no download, no row value in the answer).
 * <p>
 * <b>These are tests of the mechanisms, not of the production values</b>, which are not changed:
 * <ul>
 *   <li>statement time, global deadline and total rows: the report plan asks for less than the ceilings, which a
 *       plan may always do (A4-B4: every value is the minimum of the plan and the server);</li>
 *   <li>concurrency: the slots of the data source (4, its configuration) are held by real executions blocked before
 *       their first query, and the server's own wait (2 s) decides;</li>
 *   <li>file size: this test context lowers {@code nexora.reports.storage.max-file-bytes} to 2 048 bytes, which the
 *       configuration allows (the 20 MiB maximum can be lowered, never raised); the production default is checked
 *       unchanged by {@code ReportRunSettingsTest} and below.</li>
 * </ul>
 * Same platform as A4-B9 (real JWT, fake Semantic serving the PA-A examples rebased to this class's tenant, the bench
 * behind real TLS {@code verify-full}, tenancy approved by two persons).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportRunLimitsIntegrationTest {

    private static final UUID TENANT = UUID.fromString("b10c0000-0000-4000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("b10c0000-0000-4000-8000-000000000002");
    private static final long TEST_MAX_FILE_BYTES = 2_048;

    private static final UUID RP = rp(1);
    private static final UUID RP_STATEMENT = rp(2);
    private static final UUID RP_DEADLINE = rp(3);
    private static final UUID RP_TOTAL_ROWS = rp(4);
    private static final String ANALYST = "analyst-b10-limits";

    private static boolean registered;
    private static Path storage;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PostgresReadOnlyExecutor executor;

    @Autowired
    private CredentialProvider credentials;

    @Autowired
    private PlanV2Validator validator;

    @Autowired
    private TenantScopes tenantScopes;

    @Autowired
    private ExecutionProperties execution;

    private static UUID rp(int n) {
        return UUID.fromString("b10c0000-0000-4000-8000-0000000001" + String.format("%02d", n));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storage = Files.createTempDirectory("nexora-b10-limits");
        RunApiSetup.common(registry, storage);
        RunApiSetup.account(registry, 0, TENANT, "reports-b10-limits", "secret-b10-limits");
        RunApiSetup.source(registry, 0, TENANT, SOURCE);
        registry.add("nexora.reports.storage.max-file-bytes", () -> Long.toString(TEST_MAX_FILE_BYTES));
    }

    @BeforeAll
    static void platform() {
        RunApiSetup.dataSource(TENANT, SOURCE);
        serve(RP, RunApiSetup.paA(TENANT, SOURCE));
        ContractFixtures.PaA statement = RunApiSetup.paA(TENANT, SOURCE);
        RunApiSetup.budget(statement).put("statementTimeoutMs", 300L);
        statement.reseal();
        serve(RP_STATEMENT, statement);
        ContractFixtures.PaA deadline = RunApiSetup.paA(TENANT, SOURCE);
        RunApiSetup.budget(deadline).put("maxDurationMs", 400L);
        deadline.reseal();
        serve(RP_DEADLINE, deadline);
        ContractFixtures.PaA totalRows = RunApiSetup.paA(TENANT, SOURCE);
        // PA-A reads 15 + 1 + 35 = 51 rows.
        RunApiSetup.budget(totalRows).put("maxTotalRows", 40L);
        totalRows.reseal();
        serve(RP_TOTAL_ROWS, totalRows);
    }

    private static void serve(UUID reportPlan, ContractFixtures.PaA docs) {
        RunApiSetup.serve(TENANT, SOURCE, reportPlan, docs);
        RunApiSetup.validities(TENANT, SOURCE, reportPlan, RunApiSetup.validity(reportPlan, "CURRENT"));
    }

    private synchronized void registered() throws Exception {
        if (registered) {
            return;
        }
        String steward = token(TENANT, "steward-b10-limits", "HUMAN", "REPORTS_TENANCY_ADMIN");
        String approver = token(TENANT, "approver-b10-limits", "HUMAN", "REPORTS_TENANCY_ADMIN");
        byte[] created = mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + steward)
                        .contentType(MediaType.APPLICATION_JSON).content(RunApiSetup.BENCH_SCHEMAS))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        String id = (String) ContractFixtures.READER.readObject(created, "registration").get("id");
        mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations/" + id + "/approval")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver)).andExpect(status().isCreated());
        registered = true;
    }

    private static String analyst() {
        return token(TENANT, ANALYST, "HUMAN", "REPORT_RUNNER");
    }

    private ResultActions run(UUID reportPlan) throws Exception {
        return mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + reportPlan + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst()));
    }

    /** A REFUSED run with {@code code}, without artifact, without a download and without any row value. */
    private Map<String, Object> refused(ResultActions actions, String code, boolean readRows) throws Exception {
        actions.andExpect(status().isCreated());
        byte[] body = actions.andReturn().getResponse().getContentAsByteArray();
        Map<String, Object> doc = ContractFixtures.READER.readObject(body, "response");
        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo(code);
        assertThat(doc.get("artifact")).isNull();
        if (!readRows) {
            assertThat(l(doc.get("datasets"))).isEmpty();
        }
        String text = new String(body, StandardCharsets.UTF_8);
        RunApiSetup.wellNames().forEach(name -> assertThat(text).doesNotContain(name));
        UUID runId = UUID.fromString((String) doc.get("runId"));
        assertThat(RunApiSetup.events(runId)).doesNotContain("ARTIFACT_STORED");
        mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId + "/artifact")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())).andExpect(status().isNotFound());
        return doc;
    }

    /** Holds an ACCESS EXCLUSIVE lock on the wells of the bench while {@code action} runs. */
    private <T> T whileTheWellsAreLocked(ThrowingSupplier<T> action) throws Exception {
        try (Connection lock = TenantDatabase.superuser(TlsBench.DATABASE); Statement s = lock.createStatement()) {
            lock.setAutoCommit(false);
            s.execute("LOCK TABLE " + TlsBench.SCHEMA + ".wm_well IN ACCESS EXCLUSIVE MODE");
            try {
                return action.get();
            } finally {
                lock.rollback();
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static long filesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(directory)) {
            return s.count();
        }
    }

    // ------------------------------------------------------------------ T-8

    /**
     * Statement time: the plan asks for 300 ms; a query waiting on a lock is cancelled by {@code statement_timeout},
     * before the server's {@code lock_timeout} (2 s) could act.
     */
    @Test
    void aStatementOverItsTimeIsARefusedRunWithoutRows() throws Exception {
        registered();
        long lockTimeout = execution.limits().lockTimeout().toMillis();

        long started = System.nanoTime();
        ResultActions actions = whileTheWellsAreLocked(() -> run(RP_STATEMENT));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        refused(actions, "execution_timeout", false);
        assertThat(elapsed).isLessThan(lockTimeout);
    }

    /** Global deadline: the plan asks for 400 ms in total; every statement is capped by the time left. */
    @Test
    void aRunOverItsDeadlineIsARefusedRunWithoutRows() throws Exception {
        registered();
        long lockTimeout = execution.limits().lockTimeout().toMillis();

        long started = System.nanoTime();
        ResultActions actions = whileTheWellsAreLocked(() -> run(RP_DEADLINE));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        refused(actions, "execution_timeout", false);
        assertThat(elapsed).isLessThan(lockTimeout);
    }

    @Test
    void moreRowsThanTheTotalOfThePlanIsARefusedRunWithoutRows() throws Exception {
        registered();

        refused(run(RP_TOTAL_ROWS), "result_too_large", false);
    }

    /**
     * Concurrency: the four slots of the data source are held by real executions blocked before their first query;
     * the run waits the server's bounded time and is refused, retryable. Once the slots are free a run reads again.
     */
    @Test
    void aRunWithoutAnExecutionSlotIsARefusedRunWithoutRows() throws Exception {
        registered();
        ContractFixtures.PaA docs = RunApiSetup.paA(TENANT, SOURCE);
        ValidatedReportPlan plan = validator.validate(TENANT, SOURCE, docs.reportPlan, docs.spec, docs.plans);
        PlanV2Translator translator = new PlanV2Translator();
        List<SqlQueryV2> queries = plan.datasets().stream().map(translator::translate).toList();
        ExecutionRequestV2 request = ExecutionRequestV2.of(plan, queries);
        RunBudget budget = RunBudget.of(plan.reportPlan().budget(), execution.limits(),
                RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
        DataSourceCredentials source = credentials.resolve(TENANT, SOURCE).orElseThrow();
        int slots = source.maxConcurrent();
        CountDownLatch holding = new CountDownLatch(slots);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(slots);
        List<Future<?>> holders = new ArrayList<>();
        try {
            for (int i = 0; i < slots; i++) {
                holders.add(pool.submit(() -> ExecutorTestAccess.executeAll(executor, source, request, budget,
                        tenantScopes.forExecution(request), dataset -> {
                            if (dataset == 0) {
                                holding.countDown();
                                try {
                                    release.await(60, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        })));
            }
            assertThat(holding.await(60, TimeUnit.SECONDS)).isTrue();

            refused(run(RP), "too_many_executions", false);
        } finally {
            release.countDown();
            for (Future<?> holder : holders) {
                holder.get(60, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
        }
        // With its slot back, a run reads and renders again (its file is then over this context's lowered limit).
        refused(run(RP), "artifact_too_large", true);
    }

    /** File size, with the limit of this context lowered to 2 048 bytes (the production default is 20 MiB). */
    @Test
    void aFileOverItsSizeIsARefusedRunWithoutArtifact() throws Exception {
        registered();
        long staged = filesIn(storage.resolve("staging"));

        refused(run(RP), "artifact_too_large", true);

        assertThat(filesIn(storage.resolve("staging"))).isEqualTo(staged);
        assertThat(StorageLimits.DEFAULTS.maxFileBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(TEST_MAX_FILE_BYTES).isLessThan(StorageLimits.DEFAULTS.maxFileBytes());
    }
}
