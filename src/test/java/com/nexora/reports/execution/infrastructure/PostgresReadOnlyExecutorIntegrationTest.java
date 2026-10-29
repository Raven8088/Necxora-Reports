package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.QueryResult;
import com.nexora.reports.plan.domain.PlanValidator;
import com.nexora.reports.support.PlanFixtures;
import com.nexora.reports.support.TestDatabase;
import com.nexora.reports.translation.domain.QueryTranslator;
import com.nexora.reports.translation.domain.SqlQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.nexora.reports.support.PlanFixtures.addRowFilter;
import static com.nexora.reports.support.PlanFixtures.array;
import static com.nexora.reports.support.PlanFixtures.column;
import static com.nexora.reports.support.PlanFixtures.definition;
import static com.nexora.reports.support.PlanFixtures.element;
import static com.nexora.reports.support.PlanFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL semantics of translated plans over the synthetic database: the plans are the documents generated
 * by Semantic (or in-memory variants of them), validated and translated exactly as in production, and run through
 * the read-only executor with the {@code nexora_reader} role.
 */
class PostgresReadOnlyExecutorIntegrationTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 2, Duration.ofSeconds(2));

    private final PlanValidator validator = new PlanValidator();
    private final QueryTranslator translator = new QueryTranslator();
    private final List<ConnectionPools> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPools::close);
    }

    // --- aggregations, filters and NULL ---

    @Test
    void singleObjectPlanWithInRangeAndHaving() {
        assertThat(rows(PlanFixtures.SINGLE_OBJECT, null)).containsExactly(
                List.of("2026-01-01", "revisar", "200.500", 1L));
    }

    @Test
    void sumOfOnlyNullValuesIsNullAndCountStillCountsRows() {
        assertThat(rows(PlanFixtures.SINGLE_OBJECT, PlanFixtures::clearGroupFilters)).containsExactly(
                row("2025-12-01", "ok", "50.000", 1L),
                row("2026-01-01", "ok", "71.000", 2L),
                row("2026-01-01", "revisar", "200.500", 1L),
                row("2026-02-01", "ok", null, 1L));
    }

    @Test
    void averagesAreExactDecimals() {
        assertThat(rows(PlanFixtures.INNER_JOIN, e -> {
            element(e, "aggregations", 0).put("aggregation", "AVG");
            definition(e, "importe").put("aggregation", "AVG");
        })).extracting(r -> r.get(1)).containsExactly("75.2500000000000000", "20.0000000000000000",
                "7.0000000000000000");
    }

    @Test
    void isNotNullAndANullGroupOfItsOwn() {
        assertThat(rows(PlanFixtures.IS_NOT_NULL, null)).containsExactly(
                List.of("descartada", 1L), List.of("ok", 4L), List.of("revisar", 2L));
        assertThat(rows(PlanFixtures.IS_NOT_NULL, PlanFixtures::clearRowFilters)).containsExactly(
                row("descartada", 1L), row("ok", 4L), row("revisar", 2L), row(null, 1L));
        assertThat(rows(PlanFixtures.IS_NOT_NULL, e -> element(e, "preAggregationFilters", 0)
                .put("operator", "IS_NULL"))).containsExactly(row(null, 1L));
    }

    @Test
    void countOfAColumnIgnoresNulls() {
        assertThat(rows(PlanFixtures.SINGLE_OBJECT, e -> {
            PlanFixtures.clearGroupFilters(e);
            PlanFixtures.clearRowFilters(e);
            array(e, "outputGrain").remove(0);
            String value = column(e, "\"plant\".\"measurements\".\"value\"").get("objectId").asString();
            element(e, "aggregations", 1).put("columnObjectId", value);
            definition(e, "num_lecturas").put("columnObjectId", value);
        })).containsExactly(
                List.of("descartada", "30.000", 1L),
                List.of("ok", "121.000", 3L),
                List.of("revisar", "205.750", 2L),
                row(null, "10.000", 1L));
    }

    @Test
    void neAndInFollowThreeValuedLogic() {
        // NE excludes the reading whose note is NULL (row 5) as well as 'descartada'.
        assertThat(rows(PlanFixtures.LEFT_JOIN_FILTERS, null)).containsExactly(
                List.of("Bomba", "126.250"), List.of("Compresor", "200.500"));
        // IN never matches NULL either.
        assertThat(rows(PlanFixtures.IS_NOT_NULL, e -> {
            ObjectNode filter = element(e, "preAggregationFilters", 0);
            filter.put("operator", "IN");
            filter.putArray("values").add("ok").add("descartada");
        })).containsExactly(List.of("descartada", 1L), List.of("ok", 4L));
    }

    @Test
    void aggregationsWithoutRows() {
        Consumer<ObjectNode> nothing = e -> {
            ObjectNode filter = element(e, "preAggregationFilters", 0);
            filter.put("operator", "IN");
            filter.putArray("values").add("does-not-exist");
        };
        assertThat(rows(PlanFixtures.IS_NOT_NULL, nothing)).as("with grouping keys: no groups").isEmpty();
        assertThat(rows(PlanFixtures.IS_NOT_NULL, nothing.andThen(e -> array(e, "outputGrain").removeAll())))
                .as("without grouping keys: one row, COUNT 0").containsExactly(List.of(0L));
        assertThat(rows(PlanFixtures.SINGLE_OBJECT, e -> {
            PlanFixtures.clearGroupFilters(e);
            array(e, "outputGrain").removeAll();
            element(e, "preAggregationFilters", 0).putArray("values").add("does-not-exist");
        })).as("SUM of no rows is NULL, never 0").containsExactly(row(null, 0L));
    }

    // --- joins ---

    @Test
    void compositeCrossSchemaInnerJoinExcludesUnmatchedRows() {
        assertThat(rows(PlanFixtures.INNER_JOIN, null)).containsExactly(
                List.of("Factura A", "150.50"), List.of("Factura B", "20.00"), List.of("Factura C", "7.00"));
    }

    @Test
    void leftJoinKeepsUnmatchedRowsInANullGroup() {
        assertThat(rows(PlanFixtures.LEFT_JOIN, null)).containsExactly(
                List.of("Bomba", 2L), List.of("Compresor", 1L), row(null, 1L));
    }

    // --- time ---

    @Test
    void isoWeeksStartOnMondayInUtc() {
        assertThat(rows(PlanFixtures.WEEK, null)).containsExactly(
                List.of("2025-11-17", 1L), List.of("2025-12-29", 1L), List.of("2026-01-05", 1L),
                List.of("2026-01-12", 2L), List.of("2026-01-26", 1L), List.of("2026-02-02", 1L),
                List.of("2026-02-09", 1L));
    }

    @Test
    void timestampWithTimeZoneIsConvertedToTheDimensionZoneBeforeBucketing() {
        assertThat(rows(PlanFixtures.WEEK, e -> zone(e, "America/Toronto"))).containsExactly(
                List.of("2025-11-17", 1L), List.of("2025-12-29", 1L), List.of("2026-01-05", 2L),
                List.of("2026-01-12", 1L), List.of("2026-01-26", 1L), List.of("2026-02-02", 1L),
                List.of("2026-02-09", 1L));
        Consumer<ObjectNode> month = e -> element(e, "outputGrain", 0).put("granularity", "MONTH");
        assertThat(rows(PlanFixtures.WEEK, month)).containsExactly(List.of("2025-11-01", 1L),
                List.of("2025-12-01", 1L), List.of("2026-01-01", 3L), List.of("2026-02-01", 3L));
        assertThat(rows(PlanFixtures.WEEK, month.andThen(e -> zone(e, "America/Toronto")))).containsExactly(
                List.of("2025-11-01", 1L), List.of("2025-12-01", 1L), List.of("2026-01-01", 4L),
                List.of("2026-02-01", 2L));
        assertThat(rows(PlanFixtures.WEEK, e -> element(e, "outputGrain", 0).put("granularity", "QUARTER")))
                .containsExactly(List.of("2025-10-01", 2L), List.of("2026-01-01", 6L));
        assertThat(rows(PlanFixtures.WEEK, e -> element(e, "outputGrain", 0).put("granularity", "YEAR")))
                .containsExactly(List.of("2025-01-01", 2L), List.of("2026-01-01", 6L));
        assertThat(rows(PlanFixtures.WEEK, e -> element(e, "outputGrain", 0).put("granularity", "DAY")))
                .hasSize(8).first().isEqualTo(List.of("2025-11-20", 1L));
    }

    @Test
    void timestampWithoutTimeZoneAndDateColumnsAreBucketedAsStored() {
        assertThat(rows(PlanFixtures.WEEK, e -> {
            asWallClock(e, "timestamp");
            addRowFilter(e, "fecha", "GTE", "TIMESTAMP", "2026-01-12T02:00");
        })).containsExactly(List.of("2026-01-12", 2L), List.of("2026-01-26", 1L), List.of("2026-02-02", 1L),
                List.of("2026-02-09", 1L));
        assertThat(rows(PlanFixtures.WEEK, e -> {
            asWallClock(e, "date");
            addRowFilter(e, "fecha", "LT", "DATE", "2026-01-01");
        })).containsExactly(List.of("2025-11-17", 1L), List.of("2025-12-29", 1L));
    }

    @Test
    void instantFiltersAreComparedAsInstants() {
        // Row 8 (2026-01-12T02:00Z) is included (GTE), row 2 (2026-01-10) is not.
        assertThat(rows(PlanFixtures.WEEK, e -> addRowFilter(e, "fecha", "GTE", "TIMESTAMP_TZ",
                "2026-01-12T02:00Z"))).first().isEqualTo(List.of("2026-01-12", 2L));
    }

    // --- limits ---

    @Test
    void theRowLimitTruncatesDeterministically() {
        QueryResult result = execute(query(PlanFixtures.IS_NOT_NULL, null, 2), reader(4), LIMITS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows()).containsExactly(List.of("descartada", 1L), List.of("ok", 4L));
        assertThat(execute(query(PlanFixtures.IS_NOT_NULL, null, 3), reader(4), LIMITS).truncated()).isFalse();
    }

    @Test
    void aLockedTableHitsTheLockTimeout() throws Exception {
        ExecutionLimits limits = new ExecutionLimits(100, 1000, Duration.ofSeconds(10), Duration.ofMillis(300),
                Duration.ofSeconds(20), 50, Duration.ofSeconds(2));
        try (Connection locker = TestDatabase.superuser("enterprise"); Statement s = locker.createStatement()) {
            locker.setAutoCommit(false);
            s.execute("LOCK TABLE plant.sensors IN ACCESS EXCLUSIVE MODE");
            assertCode(() -> execute(query(PlanFixtures.LEFT_JOIN, null, 10), reader(4), limits),
                    ErrorCode.EXECUTION_TIMEOUT);
            locker.rollback();
        }
    }

    @Test
    void aSlowStatementHitsTheStatementTimeout() throws Exception {
        ExecutionLimits limits = new ExecutionLimits(100, 1000, Duration.ofMillis(300), Duration.ofSeconds(10),
                Duration.ofSeconds(20), 50, Duration.ofSeconds(2));
        try (Connection locker = TestDatabase.superuser("enterprise"); Statement s = locker.createStatement()) {
            locker.setAutoCommit(false);
            s.execute("LOCK TABLE plant.sensors IN ACCESS EXCLUSIVE MODE");
            assertCode(() -> execute(query(PlanFixtures.LEFT_JOIN, null, 10), reader(4), limits),
                    ErrorCode.EXECUTION_TIMEOUT);
            locker.rollback();
        }
    }

    @Test
    void concurrentExecutionsPerSourceAreBounded() throws Exception {
        ExecutionLimits limits = new ExecutionLimits(100, 1000, Duration.ofSeconds(10), Duration.ofSeconds(3),
                Duration.ofSeconds(20), 50, Duration.ofMillis(200));
        PostgresReadOnlyExecutor executor = executor(limits, 8);
        DataSourceCredentials oneSlot = reader(1);
        try (Connection locker = TestDatabase.superuser("enterprise"); Statement s = locker.createStatement()) {
            locker.setAutoCommit(false);
            s.execute("LOCK TABLE plant.sensors IN ACCESS EXCLUSIVE MODE");
            CompletableFuture<Throwable> blocked = CompletableFuture.supplyAsync(() -> {
                try {
                    executor.execute(oneSlot, query(PlanFixtures.LEFT_JOIN, null, 10), limits);
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(500);
            assertCode(() -> executor.execute(oneSlot, query(PlanFixtures.IS_NOT_NULL, null, 10), limits),
                    ErrorCode.TOO_MANY_EXECUTIONS);
            locker.rollback();
            blocked.get(10, TimeUnit.SECONDS);
        }
    }

    // --- safety ---

    @Test
    void injectionAttemptsInValuesAreJustData() throws Exception {
        String hostile = "x'); DROP TABLE plant.equipment; --";
        assertThat(rows(PlanFixtures.IS_NOT_NULL, e -> {
            ObjectNode filter = element(e, "preAggregationFilters", 0);
            filter.put("operator", "IN");
            filter.putArray("values").add(hostile).add("ok").add("' OR '1'='1");
        })).containsExactly(List.of("ok", 4L));
        try (Connection c = TestDatabase.superuser("enterprise"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM plant.equipment")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(3);
        }
    }

    @Test
    void identifiersWithQuotesAndSpacesAreQuotedCorrectly() {
        assertThat(rows(PlanFixtures.IS_NOT_NULL, e -> {
            String relation = "\"we\"\"ird schema\".\"tab le\"";
            ((ObjectNode) plan(e).get("base")).put("naturalKey", relation);
            PlanFixtures.find(e, "physicalObjects", "naturalKey", "\"plant\".\"measurements\"")
                    .put("naturalKey", relation);
            column(e, "\"plant\".\"measurements\".\"note\"").put("naturalKey", relation + ".\"va\"\"lue\"");
        })).containsExactly(List.of("a", 2L), List.of("b", 1L));
    }

    @Test
    void aSchemaThatNoLongerMatchesThePlanStopsTheExecution() {
        assertCode(() -> rows(PlanFixtures.IS_NOT_NULL, e -> column(e, "\"plant\".\"measurements\".\"note\"")
                .put("naturalKey", "\"plant\".\"measurements\".\"ghost\"")), ErrorCode.PHYSICAL_SCHEMA_MISMATCH);
        assertCode(() -> rows(PlanFixtures.IS_NOT_NULL, e -> column(e, "\"plant\".\"measurements\".\"note\"")
                .put("naturalKey", "\"plant\".\"measurements\".\"measured_at\"")),
                ErrorCode.PHYSICAL_SCHEMA_MISMATCH);
        assertCode(() -> rows(PlanFixtures.IS_NOT_NULL, e -> {
            ((ObjectNode) plan(e).get("base")).put("naturalKey", "\"plant\".\"gone\"");
            PlanFixtures.find(e, "physicalObjects", "naturalKey", "\"plant\".\"measurements\"")
                    .put("naturalKey", "\"plant\".\"gone\"");
            column(e, "\"plant\".\"measurements\".\"note\"").put("naturalKey", "\"plant\".\"gone\".\"note\"");
        }), ErrorCode.PHYSICAL_SCHEMA_MISMATCH);
    }

    @Test
    void aJoinWhoseTargetIsNotThePrimaryKeyInTheLiveDatabaseIsStopped() {
        // A plan claiming that equipment.dbid is the primary key (the Wellnode-like dbid case): the live primary key
        // is equipment.id, so the join could multiply rows and the execution stops.
        assertCode(() -> rows(PlanFixtures.LEFT_JOIN_FILTERS, e -> {
            String dbid = "\"plant\".\"equipment\".\"dbid\"";
            column(e, "\"plant\".\"equipment\".\"id\"").put("naturalKey", dbid);
            ((ObjectNode) element(e, "relationships", 0).get("pairs").get(0)).put("targetNaturalKey", dbid);
        }), ErrorCode.PHYSICAL_SCHEMA_MISMATCH);
    }

    @Test
    void rolesThatAreNotStrictlyReadOnlyAreRefused() {
        UUID tenant = UUID.randomUUID();
        DataSourceCredentials superuser = TestDatabase.credentials(tenant, UUID.randomUUID(),
                TestDatabase.superuserName(), TestDatabase.superuserPassword(), 2);
        assertCode(() -> execute(query(PlanFixtures.IS_NOT_NULL, null, 10), superuser, LIMITS),
                ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY);
        DataSourceCredentials writer = TestDatabase.credentials(tenant, UUID.randomUUID(), TestDatabase.WRITER,
                TestDatabase.WRITER_PASSWORD, 2);
        assertCode(() -> execute(query(PlanFixtures.IS_NOT_NULL, null, 10), writer, LIMITS),
                ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY);
    }

    @Test
    void connectionErrorsAreReportedWithoutDetails() {
        UUID tenant = UUID.randomUUID();
        DataSourceCredentials wrongPassword = TestDatabase.credentials(tenant, UUID.randomUUID(),
                TestDatabase.READER, "not-the-password", 2);
        assertCode(() -> execute(query(PlanFixtures.IS_NOT_NULL, null, 10), wrongPassword, LIMITS),
                ErrorCode.DATA_SOURCE_UNAVAILABLE);
        DataSourceCredentials closedPort = new DataSourceCredentials(tenant, UUID.randomUUID(), "127.0.0.1", 1,
                "enterprise", TestDatabase.READER, TestDatabase.READER_PASSWORD, "disable", 2);
        assertThatThrownBy(() -> execute(query(PlanFixtures.IS_NOT_NULL, null, 10), closedPort, LIMITS))
                .isInstanceOfSatisfying(ExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("password", TestDatabase.READER_PASSWORD);
                });
    }

    // --- helpers ---

    private List<List<Object>> rows(String fixture, Consumer<ObjectNode> change) {
        QueryResult result = execute(query(fixture, change, 100), reader(4), LIMITS);
        assertThat(result.truncated()).isFalse();
        return result.rows();
    }

    private SqlQuery query(String fixture, Consumer<ObjectNode> change, int maxRows) {
        ObjectNode envelope = change == null ? PlanFixtures.tree(fixture) : PlanFixtures.mutate(fixture, change);
        return translator.translate(validator.validate(PlanFixtures.read(envelope)), maxRows);
    }

    private QueryResult execute(SqlQuery query, DataSourceCredentials credentials, ExecutionLimits limits) {
        return executor(limits, 8).execute(credentials, query, limits);
    }

    private PostgresReadOnlyExecutor executor(ExecutionLimits limits, int concurrency) {
        ConnectionPools created = new ConnectionPools(limits);
        pools.add(created);
        return new PostgresReadOnlyExecutor(created, concurrency);
    }

    private static DataSourceCredentials reader(int maxConcurrent) {
        return TestDatabase.reader(PlanFixtures.TENANT, UUID.randomUUID(), maxConcurrent);
    }

    private static void zone(ObjectNode e, String zone) {
        element(e, "outputGrain", 0).put("timeZone", zone);
        definition(e, "fecha").put("timeZone", zone);
    }

    private static void asWallClock(ObjectNode e, String type) {
        String name = type.equals("date") ? "recorded_on" : "measured_local";
        ObjectNode column = column(e, "\"plant\".\"measurements\".\"measured_at\"");
        column.put("naturalKey", "\"plant\".\"measurements\".\"" + name + "\"");
        column.put("dataType", type);
        ObjectNode grain = element(e, "outputGrain", 0);
        grain.put("valueType", type.equals("date") ? "DATE" : "TIMESTAMP");
        grain.remove("timeZone");
        definition(e, "fecha").remove("timeZone");
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @SuppressWarnings("unused")
    private static ArrayNode values(ObjectNode filter) {
        return (ArrayNode) filter.get("values");
    }
}
