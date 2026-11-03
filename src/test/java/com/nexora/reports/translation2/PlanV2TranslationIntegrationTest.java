package com.nexora.reports.translation2;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B3: the translated SQL, executed in a real PostgreSQL ({@code postgres:17.6} by default) against the synthetic
 * bench of A0 ({@code nexora-testbench/reportes}, copied with its SHA-256 manifest), through the bench's read-only
 * role. PA-A must give exactly {@code esperado/pa-a.json} (byte order, nulls last, tie-breaker by {@code dbid}); plans
 * of other shapes over other tables must give what an independent, hand-written reference query gives.
 * <p>
 * The container is this class's own: the bench needs its own database and role and must not touch the shared one.
 */
class PlanV2TranslationIntegrationTest {

    private static final String BENCH = "nexora-testbench/reportes/";
    private static final String DATABASE = "nexora_bench_reportes";
    private static final String READER = "nexora_bench_lector";
    private static final String READER_PASSWORD = "bench-test-password";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse(System.getProperty("nexora.test.postgres-image", "postgres:17.6"))
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE);

    private static Map<String, Object> expected;
    private final PlanV2Translator translator = new PlanV2Translator();

    @BeforeAll
    static void loadTheBench() throws SQLException {
        POSTGRES.start();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement s = c.createStatement()) {
            for (String script : List.of("sql/01-esquema.sql", "sql/02-datos.sql", "sql/03-rol-lector.sql")) {
                s.execute(new String(ContractFixtures.bytes(BENCH + script), StandardCharsets.UTF_8));
            }
            s.execute("ALTER ROLE " + READER + " PASSWORD '" + READER_PASSWORD + "'");
        }
        expected = ContractFixtures.READER.readObject(ContractFixtures.bytes(BENCH + "esperado/pa-a.json"),
                "pa-a.json");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void theCopiedBenchIsUnaltered() throws Exception {
        String manifest = new String(ContractFixtures.bytes(BENCH + "BENCH.sha256"), StandardCharsets.UTF_8);
        List<String> lines = Arrays.stream(manifest.split("\n")).filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(4);
        for (String line : lines) {
            byte[] bytes = ContractFixtures.bytes(BENCH + line.substring(66).strip());
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .as(line.substring(66)).isEqualTo(line.substring(0, 64));
        }
    }

    @Test
    void theSummaryOfPaAIsTheExpectedOne() throws SQLException {
        List<List<String>> want = new ArrayList<>();
        for (Object row : l(expected.get("resumen"))) {
            want.add(Arrays.asList((String) m(row).get("pais"), (String) m(row).get("campo"),
                    String.valueOf(m(row).get("pozos"))));
        }

        assertThat(run(paA("resumen"))).isEqualTo(want);
    }

    @Test
    void theTotalOfPaAIsTheExpectedOne() throws SQLException {
        assertThat(run(paA("total"))).isEqualTo(List.of(List.of(String.valueOf(expected.get("total")))));
    }

    @Test
    void theDetailOfPaAIsTheExpectedOneInByteOrderWithNullsLastAndTheUniqueKeyAsTieBreaker() throws SQLException {
        List<List<String>> want = detailRows();

        List<List<String>> got = run(paA("detalle"));

        assertThat(got).isEqualTo(want);
        assertThat(got.stream().filter(r -> "Pitayo-1".equals(r.getFirst())).map(r -> r.get(5)).toList())
                .containsExactly("103", "104");
    }

    @Test
    void aJoinToThePrimaryKeyGivesTheSameRowsAsAHandWrittenReferenceQuery() throws SQLException {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);

        List<List<String>> got = run(q);

        assertThat(got).isEqualTo(reference("""
                SELECT o.name, count(*)::text FROM wellnode_sintetico.etp_company e
                  LEFT JOIN wellnode_sintetico.bkw_operators o ON o.id = e.id_operator
                 WHERE e.is_active GROUP BY o.name ORDER BY o.name COLLATE "C" NULLS LAST"""));
        assertThat(got).containsExactly(List.of("Operadora Ficticia Dos", "1"), List.of("Operadora Ficticia Uno", "3"),
                Arrays.asList(null, "1"));
    }

    @Test
    void aFilteredDetailInDescendingOrderGivesTheSameRowsAsAHandWrittenReferenceQuery() throws SQLException {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companyDetail(TestPlans.BENCH)), 100);

        assertThat(run(q)).isEqualTo(reference("""
                SELECT name, code, parentid::text, id::text FROM wellnode_sintetico.etp_company
                 WHERE code IN ('GPA', 'SIC-FLU', 'GEA', 'GPA-SER') AND name IS NOT NULL
                 ORDER BY parentid DESC NULLS FIRST, id"""));
    }

    @Test
    void readsOneRowMoreThanTheLimitToDetectTruncation() throws SQLException {
        ValidatedReportPlan.Dataset detail = TestPlans.paA().datasets().get(2);

        List<List<String>> got = run(translator.translate(detail.plan(), 3));

        assertThat(got).isEqualTo(detailRows().subList(0, 4));
    }

    // ------------------------------------------------------------------ helpers

    private SqlQueryV2 paA(String datasetId) {
        return translator.translate(TestPlans.paA().datasets().stream().filter(d -> d.datasetId().equals(datasetId))
                .findFirst().orElseThrow());
    }

    private static List<List<String>> detailRows() {
        List<List<String>> rows = new ArrayList<>();
        for (Object row : l(expected.get("detalle"))) {
            Map<String, Object> r = m(row);
            rows.add(Arrays.asList((String) r.get("nombre"), (String) r.get("campo"), (String) r.get("pais"),
                    (String) r.get("estado"), (String) r.get("estatus"), String.valueOf(r.get("dbid"))));
        }
        return rows;
    }

    /** Runs the translated query as the bench's read-only role, binding typed parameters like the R1 executor. */
    private static List<List<String>> run(SqlQueryV2 query) throws SQLException {
        try (Connection c = reader(); PreparedStatement ps = c.prepareStatement(query.sql())) {
            List<SqlParameter> parameters = query.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                SqlParameter p = parameters.get(i);
                int index = i + 1;
                switch (p.type()) {
                    case TEXT -> ps.setString(index, (String) p.value());
                    case NUMERIC -> ps.setBigDecimal(index, (BigDecimal) p.value());
                    case BOOLEAN -> ps.setBoolean(index, (Boolean) p.value());
                    case BIGINT -> ps.setLong(index, (Long) p.value());
                    case DATE -> ps.setObject(index, (LocalDate) p.value());
                    case TIMESTAMP -> ps.setObject(index, (LocalDateTime) p.value());
                    case TIMESTAMP_TZ -> ps.setObject(index, (OffsetDateTime) p.value());
                    case UUID -> ps.setObject(index, (UUID) p.value());
                    default -> throw new IllegalStateException("unexpected parameter type " + p.type());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs, query.outputs().size());
            }
        }
    }

    private static List<List<String>> reference(String sql) throws SQLException {
        try (Connection c = reader(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rows(rs, rs.getMetaData().getColumnCount());
        }
    }

    private static List<List<String>> rows(ResultSet rs, int columns) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= columns; i++) {
                row.add(rs.getString(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Connection reader() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), READER, READER_PASSWORD);
    }
}
