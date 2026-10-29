package com.nexora.reports.translation.domain;

import com.nexora.reports.plan.domain.PlanValidator;
import com.nexora.reports.plan.domain.ValidatedPlan;
import com.nexora.reports.support.PlanFixtures;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.function.Consumer;

import static com.nexora.reports.support.PlanFixtures.column;
import static com.nexora.reports.support.PlanFixtures.definition;
import static com.nexora.reports.support.PlanFixtures.element;
import static org.assertj.core.api.Assertions.assertThat;

class QueryTranslatorTest {

    private final QueryTranslator translator = new QueryTranslator();
    private final PlanValidator validator = new PlanValidator();

    @Test
    void translatesTheSingleObjectPlanIntoOneParameterizedSelect() {
        SqlQuery query = translate(PlanFixtures.SINGLE_OBJECT, null, 100);
        assertThat(query.sql()).isEqualTo("""
                SELECT CAST(date_trunc('month', "t0"."measured_at" AT TIME ZONE CAST(? AS text)) AS date), \
                "t0"."note", SUM("t0"."value"), COUNT(*)
                FROM "plant"."measurements" AS "t0"
                WHERE "t0"."note" IN (CAST(? AS text), CAST(? AS text))
                  AND "t0"."measured_at" >= CAST(? AS timestamptz)
                GROUP BY 1, 2
                HAVING SUM("t0"."value") > CAST(? AS numeric)
                ORDER BY 1 NULLS LAST, 2 NULLS LAST
                LIMIT CAST(? AS bigint)""");
        assertThat(query.parameters()).extracting(SqlParameter::type).containsExactly(SqlType.TEXT, SqlType.TEXT,
                SqlType.TEXT, SqlType.TIMESTAMP_TZ, SqlType.NUMERIC, SqlType.BIGINT);
        assertThat(query.parameters()).extracting(SqlParameter::value).containsExactly("UTC", "ok", "revisar",
                OffsetDateTime.parse("2025-12-31T23:00Z"), new BigDecimal("100"), 101L);
        assertThat(query.columns()).extracting(ResultColumn::name)
                .containsExactly("fecha", "nota", "suma_valor", "num_lecturas");
        assertThat(query.columns()).extracting(ResultColumn::type).containsExactly(ResultColumn.OutputType.DATE,
                ResultColumn.OutputType.TEXT, ResultColumn.OutputType.NUMERIC, ResultColumn.OutputType.INTEGER);
    }

    @Test
    void translatesACompositeCrossSchemaInnerJoinWithoutDistinct() {
        SqlQuery query = translate(PlanFixtures.INNER_JOIN, null, 10);
        assertThat(query.sql()).isEqualTo("""
                SELECT "t1"."title", SUM("t0"."amount")
                FROM "finance"."transactions" AS "t0"
                INNER JOIN "docs"."documents" AS "t1" ON "t0"."company_code" = "t1"."company_code" \
                AND "t0"."document_number" = "t1"."number"
                GROUP BY 1
                ORDER BY 1 NULLS LAST
                LIMIT CAST(? AS bigint)""");
        assertThat(query.sql()).doesNotContainIgnoringCase("distinct");
        assertThat(query.schema().primaryKeys()).singleElement().satisfies(k -> {
            assertThat(k.relation().naturalKey()).isEqualTo("\"docs\".\"documents\"");
            assertThat(k.columns()).containsExactly("company_code", "number");
        });
    }

    @Test
    void translatesLeftJoinsNeAndPostAggregationFilters() {
        SqlQuery query = translate(PlanFixtures.LEFT_JOIN_FILTERS, null, 10);
        assertThat(query.sql()).isEqualTo("""
                SELECT "t1"."name", SUM("t0"."value")
                FROM "plant"."measurements" AS "t0"
                LEFT JOIN "plant"."equipment" AS "t1" ON "t0"."equipment_dbid" = "t1"."id"
                WHERE "t0"."note" <> CAST(? AS text)
                GROUP BY 1
                HAVING SUM("t0"."value") <= CAST(? AS numeric)
                ORDER BY 1 NULLS LAST
                LIMIT CAST(? AS bigint)""");
    }

    @Test
    void nullChecksHaveNoParametersAndPlansWithoutDimensionsHaveNoGrouping() {
        SqlQuery query = translate(PlanFixtures.IS_NOT_NULL, e -> {
            ((tools.jackson.databind.node.ArrayNode) PlanFixtures.array(e, "outputGrain")).removeAll();
        }, 5);
        assertThat(query.sql()).isEqualTo("""
                SELECT COUNT(*)
                FROM "plant"."measurements" AS "t0"
                WHERE "t0"."note" IS NOT NULL
                LIMIT CAST(? AS bigint)""");
        assertThat(query.parameters()).hasSize(1);
    }

    @Test
    void temporalBucketsDependOnTheColumnType() {
        String week = translate(PlanFixtures.WEEK, null, 5).sql();
        assertThat(week).startsWith("SELECT CAST(date_trunc('week', \"t0\".\"measured_at\" AT TIME ZONE CAST(? AS text))"
                + " AS date), COUNT(*)");

        String wallClock = translate(PlanFixtures.WEEK, e -> asWallClock(e, "timestamp"), 5).sql();
        assertThat(wallClock).startsWith("SELECT CAST(date_trunc('week', \"t0\".\"measured_local\") AS date)");

        String date = translate(PlanFixtures.WEEK, e -> asWallClock(e, "date"), 5).sql();
        assertThat(date).startsWith(
                "SELECT CAST(date_trunc('week', CAST(\"t0\".\"recorded_on\" AS timestamp)) AS date)");
    }

    @Test
    void identifiersAreQuotedExactlyAndValuesNeverReachTheSqlText() {
        String hostile = "x'); DROP TABLE \"plant\".\"equipment\"; --";
        SqlQuery query = translate(PlanFixtures.SINGLE_OBJECT, e -> {
            column(e, "\"plant\".\"measurements\".\"note\"")
                    .put("naturalKey", "\"plant\".\"measurements\".\"no\"\"te; --\"");
            element(e, "preAggregationFilters", 0).putArray("values").add(hostile).add("ok");
        }, 10);
        assertThat(query.sql()).contains("\"t0\".\"no\"\"te; --\" IN (CAST(? AS text), CAST(? AS text))");
        assertThat(query.sql()).doesNotContain("DROP").doesNotContain("x')");
        assertThat(query.parameters()).extracting(SqlParameter::value).contains(hostile);
        assertThat(query.toString()).doesNotContain(hostile);
    }

    @Test
    void theSingleStatementGuardRejectsSeparatorsCommentsAndLiteralsOutsideIdentifiers() {
        QueryTranslator.assertSingleStatement("SELECT \"a;b\"\"--c\" FROM \"s\".\"t\" AS \"t0\"");
        QueryTranslator.assertSingleStatement("SELECT CAST(date_trunc('week', \"t0\".\"x\") AS date)");
        for (String bad : new String[]{"SELECT 1; SELECT 2", "SELECT 1 -- c", "SELECT /* c */ 1",
                "SELECT 'abc'", "SELECT \"unterminated", "SELECT \0"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> QueryTranslator.assertSingleStatement(bad))
                    .as(bad).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void translationIsDeterministic() {
        SqlQuery first = translate(PlanFixtures.LEFT_JOIN, null, 50);
        SqlQuery second = translate(PlanFixtures.LEFT_JOIN, null, 50);
        assertThat(first.sql()).isEqualTo(second.sql());
        assertThat(first.parameters()).isEqualTo(second.parameters());
        assertThat(first.maxRows()).isEqualTo(50);
    }

    /** Points the temporal dimension of the WEEK plan to a wall-clock column of another type (no time zone). */
    static void asWallClock(ObjectNode e, String type) {
        String name = type.equals("date") ? "recorded_on" : "measured_local";
        ObjectNode column = column(e, "\"plant\".\"measurements\".\"measured_at\"");
        column.put("naturalKey", "\"plant\".\"measurements\".\"" + name + "\"");
        column.put("dataType", type);
        ObjectNode grain = element(e, "outputGrain", 0);
        grain.put("valueType", type.equals("date") ? "DATE" : "TIMESTAMP");
        grain.remove("timeZone");
        definition(e, "fecha").remove("timeZone");
    }

    private SqlQuery translate(String fixture, Consumer<ObjectNode> change, int maxRows) {
        ObjectNode envelope = change == null ? PlanFixtures.tree(fixture) : PlanFixtures.mutate(fixture, change);
        ValidatedPlan plan = validator.validate(PlanFixtures.read(envelope));
        return translator.translate(plan, maxRows);
    }
}
