package com.nexora.reports.render.xlsx;

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
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.result.ReportFixtures;
import com.nexora.reports.result.domain.AssemblyInput;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.support.BenchDatabase;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B7 acceptance (precision 10): the two real Excel files, {@code PA-A.xlsx} and {@code empresas.xlsx}, produced
 * end to end from a real PostgreSQL with the synthetic bench, documents validated by B2, translated by B3, read in
 * one snapshot by B4, assembled by B6 and written by B7, by the same generic classes. Their cells are compared with
 * {@code esperado/pa-a.json} and with independent reference SQL over the bench. The files are kept in
 * {@code -Dnexora.acceptance.dir} (default {@code target/acceptance-xlsx}) for manual inspection.
 * <p>
 * As in A4-B6, the tenancy check of A4-B5 is replaced by an explicit, accepting, test-only one: the bench is not a
 * registered source.
 */
class XlsxAcceptanceIntegrationTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 100, Duration.ofSeconds(2));
    private static final RunBudget BUDGET = new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(2),
            Duration.ofSeconds(20), 100, Duration.ofSeconds(2), 30_000, RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
    /** Test-only: accepts, so that the bench, which is not registered, can be read. */
    private static final TenantScopeVerifier ACCEPT_FOR_THIS_TEST = (credentials, catalog) -> { };
    private static final Path DIRECTORY = Path.of(System.getProperty("nexora.acceptance.dir",
            "target/acceptance-xlsx"));

    private final List<ConnectionPools> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPools::close);
    }

    @Test
    void paAXlsxHasTheExpectedCells() throws IOException, SQLException {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        ReportResult result = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);
        byte[] bytes = renderTo("PA-A.xlsx", result);
        Map<String, Object> expected = RenderFixtures.paAExpected();
        try (XSSFWorkbook wb = Workbooks.open(bytes)) {
            assertThat(List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2)))
                    .containsExactly("Reporte", "Listado de pozos", "Metodología");
            List<List<Object>> report = Workbooks.rows(wb.getSheet("Reporte"));
            assertThat(report.get(indexOf(report, "Estado"))).containsExactly("Estado", "Completo");
            int header = indexOf(report, "País");
            List<Object> summary = l(expected.get("resumen"));
            for (int i = 0; i < summary.size(); i++) {
                Map<String, Object> row = m(summary.get(i));
                assertThat(report.get(header + 1 + i)).isEqualTo(Arrays.asList(orNoValue(row.get("pais")),
                        orNoValue(row.get("campo")), ((Long) row.get("pozos")).doubleValue()));
            }
            List<Object> total = report.get(header + 1 + summary.size());
            assertThat(total).isEqualTo(Arrays.asList("Total general", null,
                    ((Long) expected.get("total")).doubleValue()));
            assertThat(total.get(2)).isEqualTo(reference("SELECT count(*) FROM wellnode_sintetico.wm_well")
                    .getFirst().getFirst());
            List<List<Object>> detail = Workbooks.rows(wb.getSheet("Listado de pozos"));
            assertThat(detail.getFirst()).containsExactly("Nombre", "Campo", "País", "Estado o región", "Estatus");
            List<Object> names = new ArrayList<>();
            for (Object row : l(expected.get("detalle"))) {
                names.add(m(row).get("nombre"));
            }
            assertThat(detail.subList(1, detail.size()).stream().map(List::getFirst).toList()).isEqualTo(names);
            assertThat(Workbooks.value(wb.getSheet("Listado de pozos").getRow(1 + names.indexOf("=Cuenca-9"))
                    .getCell(0))).isEqualTo("=Cuenca-9");
            assertThat(wb.getSheet("Listado de pozos").getRow(1 + names.indexOf("=Cuenca-9")).getCell(0)
                    .getCellStyle().getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void empresasXlsxHasTheCellsOfTheReferenceSql() throws IOException, SQLException {
        ReportFixtures.Empresas documents = new ReportFixtures.Empresas(TestPlans.BENCH);
        ReportResult result = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);
        byte[] bytes = renderTo("empresas.xlsx", result);
        List<List<Object>> byOperator = reference("SELECT o.name, count(*) FROM wellnode_sintetico.etp_company e "
                + "LEFT JOIN wellnode_sintetico.bkw_operators o ON e.id_operator = o.id WHERE e.is_active = true "
                + "GROUP BY o.name ORDER BY o.name COLLATE \"C\" ASC NULLS LAST");
        Object total = reference("SELECT count(*) FROM wellnode_sintetico.etp_company WHERE is_active = true")
                .getFirst().getFirst();
        List<List<Object>> companies = reference("SELECT name, code, parentid FROM wellnode_sintetico.etp_company "
                + "WHERE code IN ('GPA', 'SIC-FLU', 'GEA', 'GPA-SER') AND name IS NOT NULL "
                + "ORDER BY parentid DESC NULLS FIRST, id ASC");
        try (XSSFWorkbook wb = Workbooks.open(bytes)) {
            assertThat(List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2)))
                    .containsExactly("Reporte", "Detalle de empresas", "Metodología");
            List<List<Object>> report = Workbooks.rows(wb.getSheet("Reporte"));
            int header = indexOf(report, "Operadora");
            assertThat(report.get(header)).containsExactly("Operadora", "Empresas");
            for (int i = 0; i < byOperator.size(); i++) {
                assertThat(report.get(header + 1 + i)).isEqualTo(List.of(orNoValue(byOperator.get(i).get(0)),
                        byOperator.get(i).get(1)));
            }
            assertThat(report.get(header + 1 + byOperator.size())).isEqualTo(List.of("Total general", total));
            assertThat(report.get(indexOf(report, "Filtros")).get(1)).isEqualTo(
                    "Empresas activas por operadora: wellnode_sintetico.etp_company.is_active es igual a Sí");
            List<List<Object>> detail = Workbooks.rows(wb.getSheet("Detalle de empresas"));
            assertThat(detail.getFirst()).containsExactly("Nombre", "Código", "Matriz");
            assertThat(detail.size()).isEqualTo(1 + companies.size());
            for (int i = 0; i < companies.size(); i++) {
                List<Object> expected = new ArrayList<>(companies.get(i));
                while (!expected.isEmpty() && expected.getLast() == null) {
                    expected.removeLast();
                }
                assertThat(detail.get(1 + i)).isEqualTo(expected);
            }
            List<List<Object>> methodology = Workbooks.rows(wb.getSheet("Metodología"));
            assertThat(methodology.get(indexOf(methodology, "Relación") + 1).get(3))
                    .isEqualTo("No disponible: el plan no indica quién aprobó la relación");
        }
    }

    @Test
    void twoRunsGiveTheSameDataCells() throws IOException {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        byte[] first = renderToMemory(run(documents.tenantId, documents.dataSourceId, documents.reportPlan,
                documents.spec, documents.plans));
        byte[] second = renderToMemory(run(documents.tenantId, documents.dataSourceId, documents.reportPlan,
                documents.spec, documents.plans));
        try (XSSFWorkbook a = Workbooks.open(first); XSSFWorkbook b = Workbooks.open(second)) {
            assertThat(Workbooks.rows(b.getSheet("Listado de pozos")))
                    .isEqualTo(Workbooks.rows(a.getSheet("Listado de pozos")));
            List<List<Object>> ra = Workbooks.rows(a.getSheet("Reporte"));
            List<List<Object>> rb = Workbooks.rows(b.getSheet("Reporte"));
            int from = indexOf(ra, "Pozos registrados");
            assertThat(rb.subList(from, rb.size())).isEqualTo(ra.subList(from, ra.size()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ReportResult run(UUID tenant, UUID source, Map<String, Object> reportPlan, Map<String, Object> spec,
                             Map<UUID, Map<String, Object>> plans) {
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
        return new ResultAssembler().assemble(new AssemblyInput(UUID.randomUUID(), plan, presentation, queries, read,
                started, Instant.now(), Instant.now(), "0.1.0-SNAPSHOT", Optional.of("Banco sintético"),
                Optional.empty(), AssemblyLimits.DEFAULTS));
    }

    private static byte[] renderTo(String name, ReportResult result) throws IOException {
        Files.createDirectories(DIRECTORY);
        Path file = DIRECTORY.resolve(name);
        RenderedArtifact artifact;
        try (OutputStream out = Files.newOutputStream(file)) {
            artifact = new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
        }
        byte[] bytes = Files.readAllBytes(file);
        assertThat(artifact.sizeBytes()).isEqualTo((long) bytes.length);
        assertThat(artifact.sha256()).isEqualTo(sha256(bytes));
        assertThat(artifact.sizeBytes() <= RenderLimits.DEFAULT_MAX_FILE_BYTES).isTrue();
        return bytes;
    }

    private static byte[] renderToMemory(ReportResult result) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
        return out.toByteArray();
    }

    /** Independent reference SQL over the bench: numbers as doubles, as Excel holds them; nulls kept. */
    private static List<List<Object>> reference(String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection c = BenchDatabase.superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    row.add(v instanceof Number number ? (Object) number.doubleValue() : v);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(List<List<Object>> rows, Object first) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && first.equals(rows.get(i).getFirst())) {
                return i;
            }
        }
        throw new AssertionError("no row starting with " + first);
    }

    private static Object orNoValue(Object value) {
        return value == null ? "(sin valor)" : value;
    }
}
