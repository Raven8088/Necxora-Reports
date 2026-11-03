package com.nexora.reports.run;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.execution.infrastructure.ConnectionPools;
import com.nexora.reports.execution.infrastructure.PostgresReadOnlyExecutor;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.render.xlsx.XlsxRenderer;
import com.nexora.reports.result.ReportFixtures;
import com.nexora.reports.result.domain.AssemblyInput;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.run.domain.FinishResult;
import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunOwner;
import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunStart;
import com.nexora.reports.run.domain.RunState;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.run.infrastructure.ArtifactStaging;
import com.nexora.reports.run.infrastructure.JdbcRunReader;
import com.nexora.reports.run.infrastructure.JdbcRunStore;
import com.nexora.reports.support.BenchDatabase;
import com.nexora.reports.support.RunRegistryDatabase;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B8 end to end: documents validated by B2, translated by B3, read in one snapshot by B4 from the synthetic bench
 * (PostgreSQL 17.6), assembled by B6, written by B7 into Reports' staging directory, recorded by B8 in Reports' own
 * database (another PostgreSQL 17.6, migrated by Flyway and used through the restricted role), and handed back to the
 * owner: the same bytes, the same SHA-256 as the artifact, a workbook that opens with the expected totals. PA-A and the
 * structurally different «Empresas» report go through the same classes. As in A4-B6 and A4-B7, the tenancy check is an
 * explicit accepting one: the bench is not a registered source.
 */
class RunRegistryAcceptanceIntegrationTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 100, Duration.ofSeconds(2));
    private static final RunBudget BUDGET = new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(2),
            Duration.ofSeconds(20), 100, Duration.ofSeconds(2), 30_000, RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
    /** Test-only: accepts, so that the bench, which is not registered, can be read. */
    private static final TenantScopeVerifier ACCEPT_FOR_THIS_TEST = (credentials, catalog) -> { };
    private static final RunOwner OWNER = new RunOwner("demo-user", RunOwner.Type.HUMAN);

    private static RunRegistryDatabase db;
    private static Path dir;
    private static ArtifactStaging staging;
    private final List<ConnectionPools> pools = new ArrayList<>();

    @BeforeAll
    static void setUp() throws IOException {
        db = RunRegistryDatabase.create("run_acceptance");
        dir = Files.createTempDirectory("b8-e2e");
        staging = new ArtifactStaging(dir.resolve("staging"));
    }

    @AfterAll
    static void tearDown() throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPools::close);
    }

    @Test
    void paAIsExecutedRenderedStoredAndHandedBackIntact() throws IOException {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        Stored s = runAndStore(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);

        assertThat(s.finish().kind()).isEqualTo(RunOutcomeKind.COMPLETED);
        assertThat(s.content().length).isEqualTo((int) s.artifact().sizeBytes());
        assertThat(sha256(s.content())).isEqualTo(s.artifact().sha256());
        assertThat(s.finish().artifact().orElseThrow().sha256()).isEqualTo(s.artifact().sha256());
        assertThat(s.view().state()).isEqualTo(RunState.COMPLETED);
        assertThat(s.view().outcome().orElseThrow().datasets()).isEqualTo(s.result().methodology().datasets());
        assertThat(s.view().outcome().orElseThrow().snapshot()).isEqualTo(s.result().run().snapshot());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(s.content()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("Reporte");
            assertThat(totalOf(wb.getSheet("Reporte"))).isEqualTo(35.0);
            assertThat(wb.getSheet("Listado de pozos").getLastRowNum()).isEqualTo(35);
        }
        assertThat(stagedFiles()).isEqualTo(0L);
    }

    @Test
    void theSecondReportGoesThroughTheSameClasses() throws IOException {
        ReportFixtures.Empresas documents = new ReportFixtures.Empresas(TestPlans.BENCH);
        Stored s = runAndStore(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);

        assertThat(s.finish().kind()).isEqualTo(RunOutcomeKind.COMPLETED);
        assertThat(sha256(s.content())).isEqualTo(s.artifact().sha256());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(s.content()))) {
            assertThat(wb.getSheetName(1)).isEqualTo("Detalle de empresas");
            assertThat(totalOf(wb.getSheet("Reporte"))).isEqualTo(5.0);
        }
        assertThat(new JdbcRunReader(db.app(), StorageLimits.DEFAULTS).open(
                new RunScope(documents.tenantId, documents.dataSourceId, "someone-else"), s.view().runId()))
                .isEqualTo(Retrieval.NOT_FOUND);
    }

    // ------------------------------------------------------------------ helpers

    private record Stored(ReportResult result, RenderedArtifact artifact, FinishResult finish, RunView view,
                          byte[] content) {
    }

    private Stored runAndStore(UUID tenant, UUID source, Map<String, Object> reportPlan, Map<String, Object> spec,
                               Map<UUID, Map<String, Object>> plans) throws IOException {
        JdbcRunStore store = new JdbcRunStore(db.app(), StorageLimits.DEFAULTS);
        JdbcRunReader reader = new JdbcRunReader(db.app(), StorageLimits.DEFAULTS);
        RunStart start = new RunStart(UUID.randomUUID(), tenant, source, UUID.randomUUID(), OWNER,
                Duration.ofMinutes(2));
        store.begin(start);

        ReportResult result = execute(start.runId(), tenant, source, reportPlan, spec, plans);
        RenderedArtifact artifact;
        FinishResult finish;
        try (ArtifactStaging.StagedFile file = staging.create()) {
            try (OutputStream out = file.openOutput()) {
                artifact = new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
            }
            finish = store.finishWithArtifact(start.ref(), RunOutcome.of(result), artifact, file.path());
        }
        RunScope scope = new RunScope(tenant, source, OWNER.subject());
        Retrieval retrieval = reader.open(scope, start.runId());
        byte[] content = ((Retrieval.Available) retrieval).content();
        RunView view = reader.find(scope, start.runId()).orElseThrow();
        return new Stored(result, artifact, finish, view, content);
    }

    private ReportResult execute(UUID runId, UUID tenant, UUID source, Map<String, Object> reportPlan,
                                 Map<String, Object> spec, Map<UUID, Map<String, Object>> plans) {
        ValidatedReportPlan plan = TestPlans.VALIDATOR.validate(tenant, source, reportPlan, spec, plans);
        SpecPresentation presentation = SpecPresentation.from(spec, plan, ContractFixtures.SHA256);
        PlanV2Translator translator = new PlanV2Translator();
        List<SqlQueryV2> queries = plan.datasets().stream().map(translator::translate).toList();
        ConnectionPools pool = new ConnectionPools(LIMITS);
        pools.add(pool);
        DataSourceCredentials credentials = BenchDatabase.reader(tenant, source, 4);
        Instant started = Instant.now();
        SnapshotRead read = new PostgresReadOnlyExecutor(pool, 16).executeAll(credentials,
                ExecutionRequestV2.of(plan, queries), BUDGET, ACCEPT_FOR_THIS_TEST);
        return new ResultAssembler().assemble(new AssemblyInput(runId, plan, presentation, queries, read, started,
                Instant.now(), Instant.now(), "0.1.0-SNAPSHOT", Optional.of("Banco sintético"), Optional.empty(),
                AssemblyLimits.DEFAULTS));
    }

    /** The value next to «Total general» on the «Reporte» sheet. */
    private static double totalOf(Sheet sheet) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && row.getCell(0).getCellType() == CellType.STRING
                    && "Total general".equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(row.getLastCellNum() - 1).getNumericCellValue();
            }
        }
        throw new AssertionError("no total");
    }

    private static long stagedFiles() throws IOException {
        try (Stream<Path> s = Files.list(staging.root())) {
            return s.count();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
