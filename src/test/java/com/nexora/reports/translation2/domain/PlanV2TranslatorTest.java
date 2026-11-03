package com.nexora.reports.translation2.domain;

import com.nexora.reports.plan.domain.NaturalKey.RelationName;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.LogicalPlanV2;
import com.nexora.reports.plan2.domain.PlanV2Exception;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.domain.TestPlans.list;
import static com.nexora.reports.plan2.domain.TestPlans.literal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B3 (R4-3, R4-10): translation of validated {@code nexora-logical-plan/2} datasets into parameterized SQL. The
 * expected texts were also executed against the synthetic bench (see {@code PlanV2TranslationIntegrationTest}).
 */
class PlanV2TranslatorTest {

    private final PlanV2Translator translator = new PlanV2Translator();

    private SqlQueryV2 paA(String datasetId) {
        ValidatedReportPlan.Dataset dataset = TestPlans.paA().datasets().stream()
                .filter(d -> d.datasetId().equals(datasetId)).findFirst().orElseThrow();
        return translator.translate(dataset);
    }

    // ------------------------------------------------------------------ real PA-A plans

    @Test
    void translatesTheSummaryOfPaAGroupedAndOrderedByBytes() {
        SqlQueryV2 q = paA("resumen");

        assertThat(q.sql()).isEqualTo("""
                SELECT * FROM (
                  SELECT "t0"."country" AS "c1",
                         "t0"."field" AS "c2",
                         COUNT(*) AS "c3"
                  FROM "wellnode_sintetico"."wm_well" AS "t0"
                 GROUP BY "t0"."country", "t0"."field"
                ) AS "q"
                ORDER BY "q"."c1" COLLATE "C" ASC NULLS LAST, "q"."c2" COLLATE "C" ASC NULLS LAST
                LIMIT CAST(? AS bigint)""");
        assertThat(q.parameters()).containsExactly(new SqlParameter(SqlType.BIGINT, 10_001L));
        assertThat(q.outputs().stream().map(SqlQueryV2.Output::output).toList())
                .containsExactly("pais", "campo", "pozos");
        assertThat(q.maxRows()).isEqualTo(10_000);
        assertThat(q.schema().relations()).containsExactly(new RelationName("wellnode_sintetico", "wm_well"));
        assertThat(q.schema().primaryKeys()).isEmpty();
    }

    @Test
    void translatesTheTotalOfPaAWithoutGroupsAndWithoutOrder() {
        assertThat(paA("total").sql()).isEqualTo("""
                SELECT * FROM (
                  SELECT COUNT(*) AS "c1"
                  FROM "wellnode_sintetico"."wm_well" AS "t0"
                ) AS "q"
                LIMIT CAST(? AS bigint)""");
    }

    @Test
    void translatesTheDetailOfPaAWithTheHiddenUniqueKeyAsLastOrderKey() {
        SqlQueryV2 q = paA("detalle");

        assertThat(q.sql()).isEqualTo("""
                SELECT * FROM (
                  SELECT "t0"."name" AS "c1",
                         "t0"."field" AS "c2",
                         "t0"."country" AS "c3",
                         "t0"."state" AS "c4",
                         "t0"."statuswell" AS "c5",
                         "t0"."dbid" AS "c6"
                  FROM "wellnode_sintetico"."wm_well" AS "t0"
                ) AS "q"
                ORDER BY "q"."c3" COLLATE "C" ASC NULLS LAST, "q"."c1" COLLATE "C" ASC NULLS LAST, \
                "q"."c6" ASC NULLS LAST
                LIMIT CAST(? AS bigint)""");
        SqlQueryV2.Output rowKey = q.outputs().getLast();
        assertThat(rowKey.position()).isEqualTo(6);
        assertThat(rowKey.hidden()).isTrue();
        assertThat(q.schema().primaryKeys()).containsExactly(new SchemaRequirementsV2.PrimaryKey(
                new RelationName("wellnode_sintetico", "wm_well"), List.of("dbid")));
        assertThat(q.schema().columns()).hasSize(6);
    }

    @Test
    void governedDefinitionsDoNotChangeTheSql() {
        LogicalPlanV2 governed = TestPlans.validated(
                ContractFixtures.read("examples/valid/pa-a.gobernado.resumen.logical-plan.json"));

        assertThat(translator.translate(governed, 10_000).sql()).isEqualTo(paA("resumen").sql());
    }

    // ------------------------------------------------------------------ other shapes, other tables

    @Test
    void translatesAJoinToThePrimaryKeyWithATypedBooleanFilter() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH)), 100);

        assertThat(q.sql()).isEqualTo("""
                SELECT * FROM (
                  SELECT "t1"."name" AS "c1",
                         COUNT(*) AS "c2"
                  FROM "wellnode_sintetico"."etp_company" AS "t0"
                  LEFT JOIN "wellnode_sintetico"."bkw_operators" AS "t1" ON "t0"."id_operator" = "t1"."id"
                 WHERE "t0"."is_active" = CAST(? AS boolean)
                 GROUP BY "t1"."name"
                ) AS "q"
                ORDER BY "q"."c1" COLLATE "C" ASC NULLS LAST
                LIMIT CAST(? AS bigint)""");
        assertThat(q.parameters()).containsExactly(new SqlParameter(SqlType.BOOLEAN, true),
                new SqlParameter(SqlType.BIGINT, 101L));
        assertThat(q.schema().relations()).containsExactly(new RelationName("wellnode_sintetico", "etp_company"),
                new RelationName("wellnode_sintetico", "bkw_operators"));
        assertThat(q.schema().primaryKeys()).containsExactly(new SchemaRequirementsV2.PrimaryKey(
                new RelationName("wellnode_sintetico", "bkw_operators"), List.of("id")));
    }

    @Test
    void translatesADetailWithInDescendingOrderAndNullsFirst() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companyDetail(TestPlans.BENCH)), 100);

        assertThat(q.sql()).isEqualTo("""
                SELECT * FROM (
                  SELECT "t0"."name" AS "c1",
                         "t0"."code" AS "c2",
                         "t0"."parentid" AS "c3",
                         "t0"."id" AS "c4"
                  FROM "wellnode_sintetico"."etp_company" AS "t0"
                 WHERE "t0"."code" IN (CAST(? AS text), CAST(? AS text), CAST(? AS text), CAST(? AS text))
                   AND "t0"."name" IS NOT NULL
                ) AS "q"
                ORDER BY "q"."c3" DESC NULLS FIRST, "q"."c4" ASC NULLS LAST
                LIMIT CAST(? AS bigint)""");
        assertThat(q.parameters()).containsExactly(new SqlParameter(SqlType.TEXT, "GPA"),
                new SqlParameter(SqlType.TEXT, "SIC-FLU"), new SqlParameter(SqlType.TEXT, "GEA"),
                new SqlParameter(SqlType.TEXT, "GPA-SER"), new SqlParameter(SqlType.BIGINT, 101L));
    }

    @Test
    void theSameCodeTranslatesThePlanOverAnInventedSchemaWithoutAnyClientName() {
        String sql = translator.translate(TestPlans.validated(TestPlans.companiesByOperator("ventas")), 100).sql();

        assertThat(sql).contains("FROM \"ventas\".\"etp_company\" AS \"t0\"")
                .contains("LEFT JOIN \"ventas\".\"bkw_operators\" AS \"t1\"")
                .doesNotContain("wellnode");
    }

    // ------------------------------------------------------------------ filters and parameters

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            EQ          | "t0"."valor" = CAST(? AS numeric)
            NE          | "t0"."valor" <> CAST(? AS numeric)
            GT          | "t0"."valor" > CAST(? AS numeric)
            GTE         | "t0"."valor" >= CAST(? AS numeric)
            LT          | "t0"."valor" < CAST(? AS numeric)
            LTE         | "t0"."valor" <= CAST(? AS numeric)
            IN          | "t0"."valor" IN (CAST(? AS numeric), CAST(? AS numeric))
            IS_NULL     | "t0"."valor" IS NULL
            IS_NOT_NULL | "t0"."valor" IS NOT NULL""")
    void translatesEveryFilterOperator(String operator, String expected) {
        List<Object> literals = switch (operator) {
            case "IN" -> list(literal("NUMBER", "1"), literal("NUMBER", "-2.5"));
            case "IS_NULL", "IS_NOT_NULL" -> list();
            default -> list(literal("NUMBER", "1"));
        };
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.oneColumn("s", "t", "valor", "NUMERIC",
                "NUMBER", operator, literals)), 10);

        assertThat(q.sql()).contains(" WHERE " + expected + "\n");
        assertThat(q.parameters()).hasSize(literals.size() + 1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            NUMERIC      | NUMBER    | NUMBER    | 0.25                                 | numeric
            TEXT         | TEXT      | TEXT      | O'Brien ñ                            | text
            UUID         | TEXT      | TEXT      | 0b0e6c1a-3b9f-4c5e-9a7d-2f1e8c4b6a90 | uuid
            BOOLEAN      | BOOLEAN   | BOOLEAN   | false                                | boolean
            DATE         | DATE      | DATE      | 2026-02-28                           | date
            TIMESTAMP    | TIMESTAMP | TIMESTAMP | 2026-02-28T13:45:10                  | timestamp
            TIMESTAMP_TZ | TIMESTAMP | TIMESTAMP_TZ | 2026-02-28T18:45:10Z              | timestamptz""")
    void typesEachParameterByTheCategoryOfItsColumn(String category, String literalType, String valueType,
                                                    String value, String sqlType) {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.oneColumn("s", "t", "valor", category,
                valueType, "EQ", list(literal(literalType, value)))), 10);

        assertThat(q.sql()).contains("\"t0\".\"valor\" = CAST(? AS " + sqlType + ")");
        Object expected = switch (category) {
            case "NUMERIC" -> new BigDecimal(value);
            case "UUID" -> UUID.fromString(value);
            case "BOOLEAN" -> Boolean.valueOf(value);
            case "DATE" -> LocalDate.parse(value);
            case "TIMESTAMP" -> LocalDateTime.parse(value);
            case "TIMESTAMP_TZ" -> OffsetDateTime.of(2026, 2, 28, 18, 45, 10, 0, ZoneOffset.UTC);
            default -> value;
        };
        assertThat(q.parameters().getFirst().value()).isEqualTo(expected);
        assertThat(q.sql()).doesNotContain(value);
    }

    @Test
    void asksForOneMoreRowThanTheEffectiveLimitToDetectTruncation() {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.companyDetail(TestPlans.BENCH)), 3);

        assertThat(q.maxRows()).isEqualTo(3);
        assertThat(q.parameters().getLast()).isEqualTo(new SqlParameter(SqlType.BIGINT, 4L));
    }

    @Test
    void producesTheSameTextAndParametersForTheSamePlan() {
        LogicalPlanV2 plan = TestPlans.validated(TestPlans.companiesByOperator(TestPlans.BENCH));

        assertThat(translator.translate(plan, 100)).isEqualTo(translator.translate(plan, 100));
    }

    // ------------------------------------------------------------------ identifiers and injection

    @Test
    void quotesIdentifiersAndDoublesTheirEmbeddedQuotes() {
        String sql = translator.translate(TestPlans.validated(TestPlans.oneColumn("we\"ird; schema", "t--x",
                "va'lor", "TEXT", "TEXT", "IS_NULL", list())), 10).sql();

        assertThat(sql).contains("FROM \"we\"\"ird; schema\".\"t--x\" AS \"t0\"").contains("\"t0\".\"va'lor\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nul\u0000byte",
            "un_identificador_de_mas_de_sesenta_y_tres_bytes_que_postgresql_truncaria"})
    void refusesIdentifiersThatPostgresqlWouldRejectOrTruncate(String name) {
        LogicalPlanV2 plan = TestPlans.validated(TestPlans.oneColumn("s", "t", name, "TEXT", "TEXT", "IS_NULL",
                list()));

        Throwable thrown = catchThrowable(() -> translator.translate(plan, 10));

        assertThat(thrown).isInstanceOf(PlanV2Exception.class);
        assertThat(((PlanV2Exception) thrown).first().rule()).isEqualTo("IDENTIFIER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesAColumnWhoseNaturalKeyNamesAnotherRelation() {
        Map<String, Object> tree = TestPlans.oneColumn("s", "t", "valor", "TEXT", "TEXT", "IS_NULL", list());
        ((Map<String, Object>) TestPlans.object(tree, TestPlans.X_VALUE).get("naturalKey")).put("name", "otra");
        LogicalPlanV2 plan = TestPlans.validated(tree);

        assertThatThrownBy(() -> translator.translate(plan, 10)).isInstanceOf(PlanV2Exception.class)
                .hasMessageContaining("IDENTIFIER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noTextOfThePlanOtherThanNaturalKeysReachesTheSql() {
        String attack = "x'); DROP TABLE t; --";
        Map<String, Object> tree = TestPlans.oneColumn("s", "t", "valor", "TEXT", "TEXT", "EQ",
                list(literal("TEXT", attack)));
        ((List<Object>) ((Map<String, Object>) tree.get("output")).get("columns")).stream()
                .map(c -> (Map<String, Object>) c).forEach(c -> c.put("label", attack));
        SqlQueryV2 q = translator.translate(TestPlans.validated(tree), 10);

        // labels, output ids ("row_key") and plan aliases ("x") never reach the text; the value is a parameter
        assertThat(q.sql()).doesNotContain("DROP").doesNotContain("row_key").doesNotContain("\"x\"");
        assertThat(q.parameters().getFirst()).isEqualTo(new SqlParameter(SqlType.TEXT, attack));
    }

    @Test
    void zeroIsNotAValidRowLimit() {
        LogicalPlanV2 plan = TestPlans.validated(TestPlans.companyDetail(TestPlans.BENCH));

        assertThatThrownBy(() -> translator.translate(plan, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ text guard

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1; SELECT 2", "SELECT 1 -- x", "SELECT 1 /* x */", "SELECT 'x'",
            "SELECT $$x$$", "SELECT E\\x", "SELECT \"unterminated", "SELECT \u0000"})
    void theTextGuardRefusesSeparatorsCommentsLiteralsAndNul(String sql) {
        assertThatThrownBy(() -> SqlTextGuard.check(sql)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theTextGuardAcceptsAnythingInsideQuotedIdentifiers() {
        SqlTextGuard.check("SELECT \"a;b\".\"c--d\".\"e'f\".\"g\"\"h\".\"$1\" FROM \"x/*y\"");
    }
}
