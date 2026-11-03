package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.LogicalPlanV2;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.support.BenchDatabase;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.plan2.domain.TestPlans.list;
import static com.nexora.reports.plan2.domain.TestPlans.literal;
import static com.nexora.reports.support.BenchDatabase.AUX;
import static com.nexora.reports.support.BenchDatabase.READER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B4 against a real PostgreSQL with the synthetic bench and its read-only role ({@link BenchDatabase}): every
 * dataset in one REPEATABLE READ, read-only transaction, typed values, truncation, limits, live schema checks before
 * reading and the mandatory tenant scope check. Plans are validated by B2 and translated by B3 exactly as in
 * production. Schema changes, locks and inserts happen only in the auxiliary schema, never in the bench.
 */
class PlanV2ExecutorIntegrationTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 100, Duration.ofSeconds(2));
    private static final UUID TENANT = UUID.fromString("b4000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("b4000000-0000-4000-8000-000000000002");
    private static final IntConsumer NOTHING = index -> { };

    private final PlanV2Translator translator = new PlanV2Translator();
    private final List<ConnectionPools> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPools::close);
    }

    // --- PA-A and consistency ---

    @Test
    void paAReadsTheThreeDatasetsInOneSnapshotWithTypedValues() {
        ValidatedReportPlan paA = TestPlans.paA();
        ControlledTenantScope scope = ControlledTenantScope.accepting();
        DataSourceCredentials credentials = BenchDatabase.reader(paA.reportPlan().tenantId(),
                paA.reportPlan().dataSourceId(), 4);

        SnapshotRead read = executor().executeAll(credentials, paARequest(paA), budget(30_000, 64L << 20), scope);

        Map<String, Object> expected = ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
        List<List<Object>> summary = new ArrayList<>();
        for (Object row : l(expected.get("resumen"))) {
            summary.add(Arrays.asList(m(row).get("pais"), m(row).get("campo"), m(row).get("pozos")));
        }
        List<List<Object>> detail = new ArrayList<>();
        for (Object row : l(expected.get("detalle"))) {
            Map<String, Object> r = m(row);
            detail.add(Arrays.asList(r.get("nombre"), r.get("campo"), r.get("pais"), r.get("estado"),
                    r.get("estatus"), r.get("dbid")));
        }
        assertThat(read.dataset("resumen").rows()).isEqualTo(summary);
        assertThat(read.dataset("total").rows()).isEqualTo(List.of(List.of(expected.get("total"))));
        assertThat(read.dataset("detalle").rows()).isEqualTo(detail);
        assertThat(read.truncated()).isFalse();
        assertThat(read.snapshot().matches("\\d+:\\d+:[\\d,]*")).isTrue();
        assertThat(scope.calls).containsExactly(credentials);
        assertThat(scope.readOnly).isEqualTo("on");
        assertThat(scope.isolation).isEqualTo("repeatable read");
    }

    @Test
    void aJoinOfTheBenchReturnsTypedCounts() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);

        SnapshotRead read = run(q);

        assertThat(read.datasets().getFirst().rows()).containsExactly(List.of("Operadora Ficticia Dos", 1L),
                List.of("Operadora Ficticia Uno", 3L), Arrays.asList(null, 1L));
    }

    @Test
    void aRowCommittedBetweenTwoQueriesIsNotSeenByTheSecond() {
        companies("consistencia");
        Map<String, Object> second = TestPlans.companiesByOperator(AUX);
        second.put("datasetId", "empresas_2");
        SqlQueryV2 q1 = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(AUX)), 100);
        SqlQueryV2 q2 = translator.translate(TestPlans.validated(second), 100);
        ExecutionRequestV2 request = new ExecutionRequestV2(TENANT, SOURCE, List.of(q1, q2));

        SnapshotRead read = executor().executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request,
                budget(30_000, 64L << 20), ControlledTenantScope.accepting(), index -> {
                    if (index == 1) {
                        BenchDatabase.exec("INSERT INTO " + AUX + ".etp_company VALUES (99, 'Nueva', 'N', NULL,"
                                + " TRUE, 1)");
                    }
                });
        SnapshotRead after = run(q1);

        assertThat(read.datasets().get(1).rows()).isEqualTo(read.datasets().get(0).rows());
        assertThat(read.datasets().get(0).rows().getFirst()).isEqualTo(List.of("Operadora A", 2L));
        assertThat(after.datasets().getFirst().rows().getFirst()).isEqualTo(List.of("Operadora A", 3L));
    }

    // --- truncation and memory ---

    @Test
    void readsOneRowMoreThanTheLimitAndKeepsOnlyTheLimit() {
        table("grande", "TEXT", "text", null);
        BenchDatabase.exec("INSERT INTO " + AUX + ".grande SELECT g, 'v' || g FROM generate_series(1, 12000) g");
        SqlQueryV2 q = translator.translate(oneColumn("grande", "TEXT", "TEXT", "IS_NOT_NULL", list()), 10_000);

        DatasetRows rows = run(q).datasets().getFirst();

        assertThat(rows.truncated()).isTrue();
        assertThat(rows.rows()).hasSize(10_000);
        assertThat(rows.rows().getFirst()).isEqualTo(List.of("v1", 1L));
        assertThat(rows.rows().getLast()).isEqualTo(List.of("v10000", 10_000L));
    }

    @Test
    void exactlyTheLimitIsNotTruncated() {
        table("justo", "TEXT", "text", null);
        BenchDatabase.exec("INSERT INTO " + AUX + ".justo SELECT g, 'v' || g FROM generate_series(1, 50) g");
        SqlQueryV2 q = translator.translate(oneColumn("justo", "TEXT", "TEXT", "IS_NOT_NULL", list()), 50);

        DatasetRows rows = run(q).datasets().getFirst();

        assertThat(rows.truncated()).isFalse();
        assertThat(rows.rows()).hasSize(50);
    }

    @Test
    void theTotalRowsOfAllDatasetsAreBounded() {
        ValidatedReportPlan paA = TestPlans.paA();

        assertCode(() -> executor().executeAll(paACredentials(paA), paARequest(paA), budget(50, 64L << 20),
                ControlledTenantScope.accepting()), ErrorCode.RESULT_TOO_LARGE);
    }

    @Test
    void theEstimatedMemoryOfAllDatasetsIsBounded() {
        ValidatedReportPlan paA = TestPlans.paA();

        assertCode(() -> executor().executeAll(paACredentials(paA), paARequest(paA), budget(30_000, 10_000),
                ControlledTenantScope.accepting()), ErrorCode.RESULT_TOO_LARGE);
    }

    // --- time limits and concurrency ---

    @Test
    void aLockedTableHitsTheLockTimeout() throws Exception {
        table("bloqueo_lock", "TEXT", "text", "1, 'a'");
        SqlQueryV2 q = translator.translate(oneColumn("bloqueo_lock", "TEXT", "TEXT", "IS_NOT_NULL", list()), 10);
        RunBudget budget = new RunBudget(Duration.ofSeconds(10), Duration.ofMillis(300), Duration.ofSeconds(20),
                100, Duration.ofSeconds(2), 1000, 1 << 20);

        whileLocked("bloqueo_lock", () -> assertCode(() -> run(q, budget), ErrorCode.EXECUTION_TIMEOUT));
    }

    @Test
    void aSlowStatementHitsTheStatementTimeout() throws Exception {
        table("bloqueo_sentencia", "TEXT", "text", "1, 'a'");
        SqlQueryV2 q = translator.translate(oneColumn("bloqueo_sentencia", "TEXT", "TEXT", "IS_NOT_NULL", list()),
                10);
        RunBudget budget = new RunBudget(Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ofSeconds(20),
                100, Duration.ofSeconds(2), 1000, 1 << 20);

        whileLocked("bloqueo_sentencia", () -> assertCode(() -> run(q, budget), ErrorCode.EXECUTION_TIMEOUT));
    }

    @Test
    void theWholeExecutionHasOneDeadlineThatCapsEveryStatement() throws Exception {
        table("bloqueo_plazo", "TEXT", "text", "1, 'a'");
        SqlQueryV2 q = translator.translate(oneColumn("bloqueo_plazo", "TEXT", "TEXT", "IS_NOT_NULL", list()), 10);
        RunBudget budget = new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofMillis(1500),
                100, Duration.ofSeconds(2), 1000, 1 << 20);

        whileLocked("bloqueo_plazo", () -> {
            long start = System.nanoTime();
            assertCode(() -> run(q, budget), ErrorCode.EXECUTION_TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis() < 5_000).isTrue();
        });
    }

    @Test
    void anExecutionHoldsItsSlotUntilItEnds() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);
        PostgresReadOnlyExecutor executor = executor();
        DataSourceCredentials oneSlot = BenchDatabase.reader(TENANT, SOURCE, 1);
        RunBudget budget = new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(20), 100,
                Duration.ofMillis(200), 1000, 1 << 20);
        AtomicReference<Throwable> inner = new AtomicReference<>();

        SnapshotRead outer = executor.executeAll(oneSlot, request(q), budget, ControlledTenantScope.accepting(),
                index -> inner.set(catchThrowable(() -> executor.executeAll(oneSlot, request(q), budget,
                        ControlledTenantScope.accepting()))));

        assertThat(outer.datasets().getFirst().rows()).hasSize(3);
        assertThat(inner.get()).isInstanceOf(ExecutionException.class);
        assertThat(((ExecutionException) inner.get()).code()).isEqualTo(ErrorCode.TOO_MANY_EXECUTIONS);
    }

    // --- live schema, before any data is read (R4-9) ---

    @ParameterizedTest
    @ValueSource(strings = {"renamed", "type", "primary_key", "column_privilege", "dropped"})
    void aChangedLiveSchemaStopsTheExecutionBeforeReading(String change) {
        String table = "cambio_" + change;
        BenchDatabase.exec("CREATE TABLE " + AUX + "." + table + " (id integer PRIMARY KEY, valor text)",
                "INSERT INTO " + AUX + "." + table + " VALUES (1, 'a')",
                "GRANT SELECT (id, valor) ON " + AUX + "." + table + " TO " + READER);
        SqlQueryV2 q = translator.translate(oneColumn(table, "TEXT", "TEXT", "IS_NOT_NULL", list()), 10);
        BenchDatabase.exec(switch (change) {
            case "renamed" -> "ALTER TABLE " + AUX + "." + table + " RENAME COLUMN valor TO valor2";
            case "type" -> "ALTER TABLE " + AUX + "." + table + " ALTER COLUMN valor TYPE integer USING 1";
            case "primary_key" -> "ALTER TABLE " + AUX + "." + table + " DROP CONSTRAINT " + table + "_pkey";
            case "column_privilege" -> "REVOKE SELECT (valor) ON " + AUX + "." + table + " FROM " + READER;
            default -> "DROP TABLE " + AUX + "." + table;
        });
        ControlledTenantScope scope = ControlledTenantScope.accepting();
        AtomicInteger queries = new AtomicInteger();

        assertCode(() -> executor().executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request(q), defaultBudget(),
                scope, index -> queries.incrementAndGet()), ErrorCode.PHYSICAL_SCHEMA_MISMATCH);
        assertThat(scope.calls).hasSize(1);
        assertThat(queries.get()).isEqualTo(0);
    }

    @Test
    void aRoleThatCanWriteARelationReadIsRefused() {
        table("escritura", "TEXT", "text", "1, 'a'");
        BenchDatabase.exec("GRANT INSERT ON " + AUX + ".escritura TO " + READER);
        SqlQueryV2 q = translator.translate(oneColumn("escritura", "TEXT", "TEXT", "IS_NOT_NULL", list()), 10);
        try {
            assertCode(() -> run(q), ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY);
        } finally {
            BenchDatabase.exec("REVOKE INSERT ON " + AUX + ".escritura FROM " + READER);
        }
    }

    // --- typed parameters and values ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            numerico      | NUMERIC      | NUMBER    | NUMBER       | 0.25
            texto         | TEXT         | TEXT      | TEXT         | O'Brien ñ
            identificador | UUID         | TEXT      | TEXT         | 0b0e6c1a-3b9f-4c5e-9a7d-2f1e8c4b6a90
            logico        | BOOLEAN      | BOOLEAN   | BOOLEAN      | false
            fecha         | DATE         | DATE      | DATE         | 2026-02-28
            momento       | TIMESTAMP    | TIMESTAMP | TIMESTAMP    | 2026-02-28T13:45:10
            instante      | TIMESTAMP_TZ | TIMESTAMP | TIMESTAMP_TZ | 2026-02-28T18:45:10Z""")
    void bindsEachParameterTypeAndReadsEachValueTypeExactly(String column, String category, String literalType,
                                                            String valueType, String value) {
        types();
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.oneColumn(AUX, "tipos", column, category,
                valueType, "EQ", list(literal(literalType, value)))), 10);

        List<List<Object>> rows = run(q).datasets().getFirst().rows();

        Object expected = switch (category) {
            case "NUMERIC" -> new BigDecimal(value);
            case "BOOLEAN" -> Boolean.valueOf(value);
            case "DATE" -> LocalDate.parse(value);
            case "TIMESTAMP" -> LocalDateTime.parse(value);
            case "TIMESTAMP_TZ" -> OffsetDateTime.parse(value);
            default -> value;
        };
        assertThat(rows).isEqualTo(List.of(List.of(expected, 1L)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            nan       | NUMERIC | NUMBER
            infinita  | DATE    | DATE
            fraccion  | NUMERIC | INTEGER""")
    void valuesWithoutAnExactRepresentationAreRefused(String column, String category, String valueType) {
        BenchDatabase.exec("CREATE TABLE IF NOT EXISTS " + AUX + ".raros (id integer PRIMARY KEY, nan numeric,"
                        + " infinita date, fraccion numeric)",
                "INSERT INTO " + AUX + ".raros VALUES (1, 'NaN', 'infinity', 2.5) ON CONFLICT DO NOTHING",
                "GRANT SELECT ON " + AUX + ".raros TO " + READER);
        SqlQueryV2 q = translator.translate(oneColumn("raros", column, category, valueType, "IS_NOT_NULL", list()),
                10);

        assertCode(() -> run(q), ErrorCode.VALUE_NOT_REPRESENTABLE);
    }

    // --- tenant scope, identity and transaction hygiene ---

    @Test
    void aRefusingTenantScopeCheckStopsTheExecutionBeforeAnyQuery() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);
        ControlledTenantScope scope = ControlledTenantScope.refusing(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED);
        AtomicInteger queries = new AtomicInteger();

        assertCode(() -> executor().executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request(q), defaultBudget(),
                scope, index -> queries.incrementAndGet()), ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED);
        assertThat(scope.calls).hasSize(1);
        assertThat(queries.get()).isEqualTo(0);
    }

    @Test
    void thereIsNoExecutionWithoutATenantScopeCheck() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);

        assertThatThrownBy(() -> executor().executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request(q),
                defaultBudget(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void credentialsOfAnotherTenantAreRefusedBeforeConnecting() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);
        ControlledTenantScope scope = ControlledTenantScope.accepting();

        assertThatThrownBy(() -> executor().executeAll(BenchDatabase.reader(UUID.randomUUID(), SOURCE, 4),
                request(q), defaultBudget(), scope)).isInstanceOf(IllegalArgumentException.class);
        assertThat(scope.calls).isEmpty();
    }

    @Test
    void connectionsGoBackToThePoolWithoutAnOpenTransaction() throws Exception {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);
        PostgresReadOnlyExecutor executor = executor();
        executor.executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request(q), defaultBudget(),
                ControlledTenantScope.accepting());
        catchThrowable(() -> executor.executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request(q),
                defaultBudget(), ControlledTenantScope.refusing(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED)));

        try (Connection c = BenchDatabase.superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename = '" + READER
                     + "' AND state LIKE 'idle in transaction%'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(0L);
        }
    }

    // ------------------------------------------------------------------ helpers

    private PostgresReadOnlyExecutor executor() {
        ConnectionPools pool = new ConnectionPools(LIMITS);
        pools.add(pool);
        return new PostgresReadOnlyExecutor(pool, 16);
    }

    private static RunBudget budget(int maxTotalRows, long maxEstimatedBytes) {
        return new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(20), 100,
                Duration.ofSeconds(2), maxTotalRows, maxEstimatedBytes);
    }

    private static RunBudget defaultBudget() {
        return budget(30_000, RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
    }

    private ExecutionRequestV2 paARequest(ValidatedReportPlan paA) {
        return ExecutionRequestV2.of(paA, paA.datasets().stream().map(translator::translate).toList());
    }

    private static DataSourceCredentials paACredentials(ValidatedReportPlan paA) {
        return BenchDatabase.reader(paA.reportPlan().tenantId(), paA.reportPlan().dataSourceId(), 4);
    }

    private static ExecutionRequestV2 request(SqlQueryV2 query) {
        return new ExecutionRequestV2(TENANT, SOURCE, List.of(query));
    }

    private SnapshotRead run(SqlQueryV2 query) {
        return run(query, defaultBudget());
    }

    private SnapshotRead run(SqlQueryV2 query, RunBudget budget) {
        return executor().executeAll(BenchDatabase.reader(TENANT, SOURCE, 4), request(query), budget,
                ControlledTenantScope.accepting(), NOTHING);
    }

    private static LogicalPlanV2 oneColumn(String table, String category, String valueType, String operator,
                                           List<Object> literals) {
        return oneColumn(table, "valor", category, valueType, operator, literals);
    }

    private static LogicalPlanV2 oneColumn(String table, String column, String category, String valueType,
                                           String operator, List<Object> literals) {
        return TestPlans.validated(TestPlans.oneColumn(AUX, table, column, category, valueType, operator, literals));
    }

    /** An auxiliary table {@code (id integer PRIMARY KEY, valor <type>)} readable by the bench role. */
    private static void table(String name, String category, String type, String values) {
        BenchDatabase.exec("DROP TABLE IF EXISTS " + AUX + "." + name,
                "CREATE TABLE " + AUX + "." + name + " (id integer PRIMARY KEY, valor " + type + ")",
                "GRANT SELECT ON " + AUX + "." + name + " TO " + READER);
        if (values != null) {
            BenchDatabase.exec("INSERT INTO " + AUX + "." + name + " VALUES (" + values + ")");
        }
    }

    private static void companies(String marker) {
        BenchDatabase.exec("DROP TABLE IF EXISTS " + AUX + ".etp_company",
                "DROP TABLE IF EXISTS " + AUX + ".bkw_operators",
                "CREATE TABLE " + AUX + ".bkw_operators (id integer PRIMARY KEY, name text)",
                "CREATE TABLE " + AUX + ".etp_company (id integer PRIMARY KEY, name text, code text,"
                        + " parentid integer, is_active boolean, id_operator integer)",
                "INSERT INTO " + AUX + ".bkw_operators VALUES (1, 'Operadora A'), (2, 'Operadora B')",
                "INSERT INTO " + AUX + ".etp_company VALUES (1, '" + marker + "', 'C1', NULL, TRUE, 1),"
                        + " (2, 'Dos', 'C2', NULL, TRUE, 1), (3, 'Tres', 'C3', NULL, TRUE, 2),"
                        + " (4, 'Cuatro', 'C4', NULL, FALSE, 1)",
                "GRANT SELECT ON " + AUX + ".bkw_operators, " + AUX + ".etp_company TO " + READER);
    }

    private static void types() {
        BenchDatabase.exec("CREATE TABLE IF NOT EXISTS " + AUX + ".tipos (id integer PRIMARY KEY, numerico numeric,"
                        + " texto text, identificador uuid, logico boolean, fecha date, momento timestamp,"
                        + " instante timestamptz)",
                "INSERT INTO " + AUX + ".tipos VALUES"
                        + " (1, 0.25, 'O''Brien ñ', '0b0e6c1a-3b9f-4c5e-9a7d-2f1e8c4b6a90', FALSE, '2026-02-28',"
                        + " '2026-02-28 13:45:10', '2026-02-28 13:45:10-05'),"
                        + " (2, 0.5, 'otro', '11111111-1111-4111-8111-111111111111', TRUE, '2026-03-01',"
                        + " '2026-03-01 00:00:00', '2026-03-01 00:00:00+00') ON CONFLICT DO NOTHING",
                "GRANT SELECT ON " + AUX + ".tipos TO " + READER);
    }

    /** Runs the check while another session holds an ACCESS EXCLUSIVE lock on the auxiliary table. */
    private static void whileLocked(String table, Runnable check) throws Exception {
        try (Connection locker = BenchDatabase.superuser(); Statement s = locker.createStatement()) {
            locker.setAutoCommit(false);
            s.execute("LOCK TABLE " + AUX + "." + table + " IN ACCESS EXCLUSIVE MODE");
            try {
                check.run();
            } finally {
                locker.rollback();
            }
        }
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(((ExecutionException) thrown).code()).isEqualTo(code);
    }
}
