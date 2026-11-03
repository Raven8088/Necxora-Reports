package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.ContractFixtures.PaA;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.nexora.reports.plan2.ContractFixtures.copy;
import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B2 (R4-10): independent validation in Reports of a report plan, its specification and its logical plans.
 * Positive cases use the real PA-A documents of the contracts; negative cases derive from them in memory. Unless a
 * case is about fingerprints, every fingerprint is recomputed after the mutation, so the case isolates its rule.
 */
class PlanV2ValidatorTest {

    private static final ContractSchemas SCHEMAS = ContractFixtures.schemas();
    private static final PlanV2Validator VALIDATOR = new PlanV2Validator(SCHEMAS, ReportsCeilings.DEFAULTS,
            ContractFixtures.SHA256);

    private static final String DBID = "7a3f0000-0000-4000-8000-000000000011";
    private static final String FIELD = "7a3f0000-0000-4000-8000-000000000013";
    private static final String STATUS = "7a3f0000-0000-4000-8000-000000000016";
    private static final String OTHER_TABLE = "7a3f0000-0000-4000-8000-0000000000a0";
    private static final String OTHER_PK = "7a3f0000-0000-4000-8000-0000000000a1";
    private static final String OTHER_LABEL = "7a3f0000-0000-4000-8000-0000000000a2";
    private static final String RELATIONSHIP = "7a3f0000-0000-4000-8000-0000000000b0";

    // ------------------------------------------------------------------ positive

    @Test
    void acceptsTheRealPaAReportPlan() {
        PaA paA = new PaA();

        ValidatedReportPlan validated = VALIDATOR.validate(paA.tenantId, paA.dataSourceId, paA.reportPlan, paA.spec,
                paA.plans);

        assertThat(validated.datasets().stream().map(ValidatedReportPlan.Dataset::datasetId).toList())
                .containsExactly("resumen", "total", "detalle");
        assertThat(validated.datasets().stream().map(ValidatedReportPlan.Dataset::maxRows).toList())
                .containsExactly(10_000, 10_000, 10_000);
        assertThat(validated.reportPlan().budget().maxTotalRows()).isEqualTo(30_000);
        assertThat(validated.spec().status()).isEqualTo("READY");
        assertThat(validated.spec().fingerprint()).isEqualTo(paA.reportPlan.get("specFingerprint"));

        LogicalPlanV2 detail = validated.datasets().get(2).plan();
        assertThat(detail.operations().get(0)).isInstanceOf(LogicalPlanV2.Scan.class);
        assertThat(detail.operations().get(1)).isInstanceOf(LogicalPlanV2.Project.class);
        assertThat(detail.operations().get(2)).isInstanceOf(LogicalPlanV2.Order.class);
        assertThat(detail.operations().get(3)).isEqualTo(new LogicalPlanV2.Limit(10_000));
        LogicalPlanV2.OutputColumn rowKey = detail.output().getLast();
        assertThat(rowKey.output()).isEqualTo("row_key");
        assertThat(rowKey.role()).isEqualTo("KEY");
        assertThat(rowKey.hidden()).isTrue();
        LogicalPlanV2.Order order = (LogicalPlanV2.Order) detail.operations().get(2);
        assertThat(order.tieBreaker()).isEqualTo("UNIQUE_KEY");
        assertThat(order.keys().get(0).collation()).isEqualTo("C");
        assertThat(order.keys().get(2).collation()).isNull();
        assertThat(detail.object(UUID.fromString(DBID)).orElseThrow().typeCategory())
                .isEqualTo(LogicalPlanV2.Category.NUMERIC);
    }

    @Test
    void acceptsTheGovernedPlanOfX8() {
        Map<String, Object> governed = ContractFixtures.read("examples/valid/pa-a.gobernado.resumen.logical-plan.json");

        assertThat(VALIDATOR.checkLogicalPlan(governed, "gobernado")).isEmpty();
    }

    @Test
    void acceptsAJoinThatReachesExactlyThePrimaryKeyOfTheJoinedTable() {
        PaA paA = new PaA();
        addJoin(paA);
        paA.reseal();

        ValidatedReportPlan validated = VALIDATOR.validate(paA.tenantId, paA.dataSourceId, paA.reportPlan, paA.spec,
                paA.plans);

        LogicalPlanV2.Join join = (LogicalPlanV2.Join) validated.datasets().get(2).plan().operations().get(1);
        assertThat(join.alias()).isEqualTo("j");
        assertThat(join.conditions()).hasSize(1);
    }

    static Stream<Arguments> canonicalFilters() {
        return Stream.of(
                Arguments.of(Named.of("IN with accents", (Consumer<PaA>) p -> addFilter(p, STATUS, "IN",
                        List.of(literal("TEXT", "Activo"), literal("TEXT", "Ñandú"))))),
                Arguments.of(Named.of("NUMBER range", (Consumer<PaA>) p -> addFilter(p, DBID, "GTE",
                        List.of(literal("NUMBER", "-10.25"))))),
                Arguments.of(Named.of("IS_NULL", (Consumer<PaA>) p -> addFilter(p, STATUS, "IS_NULL", List.of()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("canonicalFilters")
    void acceptsFiltersWithCanonicalLiterals(Consumer<PaA> filter) {
        PaA paA = new PaA();
        filter.accept(paA);
        paA.reseal();

        ValidatedReportPlan validated = VALIDATOR.validate(paA.tenantId, paA.dataSourceId, paA.reportPlan, paA.spec,
                paA.plans);

        assertThat(validated.datasets().get(2).plan().operations().get(1)).isInstanceOf(LogicalPlanV2.Filter.class);
    }

    // ------------------------------------------------------------------ negative

    private record Case(Consumer<PaA> mutation, boolean reseal, String rule) {
    }

    private static Arguments refused(String name, String rule, boolean reseal, Consumer<PaA> mutation) {
        return Arguments.of(Named.of(name, new Case(mutation, reseal, rule)));
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                // fingerprints (no reseal: the mutation itself is the inconsistency)
                refused("altered logical plan", "FINGERPRINT", false,
                        p -> m(l(m(p.plan("detalle").get("output")).get("columns")).get(0)).put("label", "Otro")),
                refused("altered report plan", "FINGERPRINT", false,
                        p -> p.reportPlan.put("fingerprint", "0".repeat(64))),
                refused("planFingerprint of another plan", "FINGERPRINT", false, p -> {
                    m(l(p.reportPlan.get("plans")).get(0)).put("planFingerprint", "a".repeat(64));
                    p.reportPlan.put("fingerprint", CanonicalJson.fingerprintOf(p.reportPlan, ContractFixtures.SHA256));
                }),
                refused("altered specification", "FINGERPRINT", false, p -> p.spec.put("title", "Otro título")),
                // identities
                refused("request for another tenant", "IDENTITY", false, p -> p.tenantId = UUID.randomUUID()),
                refused("request for another data source", "IDENTITY", false,
                        p -> p.dataSourceId = UUID.randomUUID()),
                refused("plan of another tenant", "IDENTITY", true,
                        p -> p.plan("total").put("tenantId", "00000000-0000-4000-8000-000000000999")),
                refused("missing logical plan", "IDENTITY", false,
                        p -> p.plans.remove(p.plans.keySet().iterator().next())),
                refused("logical plan not in the report plan", "IDENTITY", false,
                        p -> p.plans.put(UUID.randomUUID(), copy(p.plan("total")))),
                refused("plan of another dataset", "IDENTITY", true, p -> p.plan("total").put("datasetId", "otro")),
                refused("plans from two metadata snapshots", "IDENTITY", true,
                        p -> p.plan("total").put("metadataSnapshotId", "00000000-0000-4000-8000-000000000998")),
                refused("specification with an unknown dataset reference", "SPEC-R6", true,
                        p -> m(l(p.spec.get("datasets")).get(1)).put("id", "otro")),
                // contract
                refused("unknown field in a plan", "SCHEMA", false, p -> p.plan("total").put("sql", "select 1")),
                refused("unknown field in the specification", "SCHEMA", false, p -> p.spec.put("sql", "select 1")),
                refused("total ordered by group keys", "PLAN-R3", true,
                        p -> m(m(p.operations("total").get(2)).get("tieBreaker")).put("kind", "GROUP_KEYS")),
                // profile basic-a
                refused("report plan in profile basic-b", "BASIC-A", true, p -> p.reportPlan.put("profile", "basic-b")),
                refused("GROUP_FILTER", "BASIC-A", true, p -> p.operations("resumen").add(2, map("op", "GROUP_FILTER",
                        "predicates", list(map("output", "pozos", "operator", "GT",
                                "values", list(literal("NUMBER", "1"))))))),
                refused("SUM", "BASIC-A", true, p -> {
                    Map<String, Object> measure = m(l(m(p.operations("total").get(1)).get("measures")).get(0));
                    measure.put("aggregation", "SUM");
                    measure.put("column", map("alias", "w", "column", DBID));
                }),
                refused("COUNT_DISTINCT", "BASIC-A", true,
                        p -> m(l(m(p.operations("total").get(1)).get("measures")).get(0))
                                .put("aggregation", "COUNT_DISTINCT")),
                refused("parameters", "BASIC-A", true, p -> l(p.plan("total").get("parameters"))
                        .add(map("name", "idioma", "source", "INSTALLATION", "valueType", "TEXT"))),
                refused("TIME column", "BASIC-A", true, p -> object(p, STATUS).put("typeCategory", "TIME")),
                refused("OTHER column", "BASIC-A", true, p -> object(p, STATUS).put("typeCategory", "OTHER")),
                refused("collation on a numeric key", "BASIC-A", true,
                        p -> m(l(m(p.operations("detalle").get(2)).get("keys")).get(2)).put("collation", "C")),
                refused("text key without byte order", "BASIC-A", true,
                        p -> m(l(m(p.operations("detalle").get(2)).get("keys")).get(0)).remove("collation")),
                refused("output type not matching its column", "BASIC-A", true,
                        p -> m(l(m(p.plan("detalle").get("output")).get("columns")).get(0)).put("valueType", "DATE")),
                refused("NUMBER literal on a text column", "BASIC-A", true,
                        p -> addFilter(p, STATUS, "EQ", List.of(literal("NUMBER", "1")))),
                refused("range on a text column", "BASIC-A", true,
                        p -> addFilter(p, STATUS, "GT", List.of(literal("TEXT", "a")))),
                refused("IN without values", "BASIC-A", true, p -> addFilter(p, STATUS, "IN", List.of())),
                refused("number with trailing zeros", "BASIC-A", true,
                        p -> addFilter(p, DBID, "EQ", List.of(literal("NUMBER", "10.0")))),
                refused("number with exponent", "BASIC-A", true,
                        p -> addFilter(p, DBID, "EQ", List.of(literal("NUMBER", "1e3")))),
                // joins
                refused("join through an OBSERVED relationship", "BASIC-A", true, p -> {
                    addJoin(p);
                    relationship(p).put("status", "OBSERVED");
                }),
                refused("join to a unique constraint", "BASIC-A", true, p -> {
                    addJoin(p);
                    relationship(p).put("targetUniqueness", "UNIQUE_CONSTRAINT");
                }),
                refused("join type not allowed by the relationship", "BASIC-A", true, p -> {
                    addJoin(p);
                    join(p).put("type", "INNER");
                }),
                refused("join on a parameter", "BASIC-A", true, p -> {
                    addJoin(p);
                    Map<String, Object> condition = m(l(join(p).get("conditions")).get(0));
                    condition.remove("right");
                    condition.put("parameter", "p");
                }),
                refused("join to a column that is not the primary key", "BASIC-A", true, p -> {
                    addJoin(p);
                    m(m(l(join(p).get("conditions")).get(0)).get("right")).put("column", OTHER_LABEL);
                    m(l(relationship(p).get("pairs")).get(0)).put("target", OTHER_LABEL);
                }),
                refused("join conditions not matching the relationship", "BASIC-A", true, p -> {
                    addJoin(p);
                    m(l(relationship(p).get("pairs")).get(0)).put("source", DBID);
                }),
                refused("join through a relationship not in the plan", "BASIC-A", true, p -> {
                    addJoin(p);
                    l(p.plan("detalle").get("relationships")).clear();
                }),
                refused("reverse join towards the non-unique side", "BASIC-A", true, p -> {
                    addJoin(p);
                    join(p).put("direction", "REVERSE");
                }),
                refused("column read through the wrong alias", "BASIC-A", true, p -> {
                    addJoin(p);
                    m(m(l(m(p.operations("detalle").get(2)).get("columns")).get(0)).get("column")).put("alias", "j");
                }),
                // budgets
                refused("LIMIT above the report plan budget", "BUDGET", true,
                        p -> m(p.operations("total").get(3)).put("maxRows", 20_000L)),
                refused("budget above the ceiling of Reports", "BUDGET", true,
                        p -> m(p.reportPlan.get("budget")).put("maxRowsPerPlan", 50_000L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void refusesAndNamesTheFailedRule(Case c) {
        PaA paA = new PaA();
        c.mutation().accept(paA);
        if (c.reseal()) {
            paA.reseal();
        }

        PlanV2Exception e = refusal(() -> VALIDATOR.validate(paA.tenantId,
                paA.dataSourceId, paA.reportPlan, paA.spec, paA.plans));

        assertThat(e.violations().stream().map(PlanV2Violation::rule).toList()).contains(c.rule());
    }

    @Test
    void refusesAPlanWhoseSpecificationFingerprintDiffersEvenIfItsOwnFingerprintIsConsistent() {
        PaA paA = new PaA();
        Map<String, Object> total = paA.plan("total");
        total.put("specFingerprint", "b".repeat(64));
        total.put("fingerprint", CanonicalJson.fingerprintOf(total, ContractFixtures.SHA256));
        for (Object ref : l(paA.reportPlan.get("plans"))) {
            if ("total".equals(m(ref).get("datasetId"))) {
                m(ref).put("planFingerprint", total.get("fingerprint"));
            }
        }
        paA.reportPlan.put("fingerprint", CanonicalJson.fingerprintOf(paA.reportPlan, ContractFixtures.SHA256));

        PlanV2Exception e = refusal(() -> VALIDATOR.validate(paA.tenantId,
                paA.dataSourceId, paA.reportPlan, paA.spec, paA.plans));

        assertThat(e.violations()).contains(new PlanV2Violation("FINGERPRINT",
                PlanV2Violation.Kind.FINGERPRINT_MISMATCH, "logical-plan[total].specFingerprint"));
    }

    @Test
    void refusesPlansWithMoreColumnsThanTheCeiling() {
        PaA paA = new PaA();
        PlanV2Validator narrow = new PlanV2Validator(SCHEMAS, new ReportsCeilings(3, 10_000, 30_000, 3, 30_000,
                60_000), ContractFixtures.SHA256);

        PlanV2Exception e = refusal(() -> narrow.validate(paA.tenantId,
                paA.dataSourceId, paA.reportPlan, paA.spec, paA.plans));

        assertThat(e.violations()).contains(new PlanV2Violation("BUDGET", PlanV2Violation.Kind.BUDGET_EXCEEDED,
                "logical-plan[detalle].output.columns"));
    }

    @Test
    void violationsNameLocationsButNeverCopyValuesFromTheDocuments() {
        PaA paA = new PaA();
        paA.spec.put("secreto", "Pozo confidencial 42");
        paA.reseal();

        assertThatThrownBy(() -> VALIDATOR.validate(paA.tenantId, paA.dataSourceId, paA.reportPlan, paA.spec,
                paA.plans))
                .isInstanceOf(PlanV2Exception.class)
                .hasMessageContaining("spec.secreto")
                .hasMessageNotContaining("confidencial");
    }

    // ------------------------------------------------------------------ helpers

    private static PlanV2Exception refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PlanV2Exception.class);
        return (PlanV2Exception) thrown;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<Object> list(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    private static Map<String, Object> literal(String type, String value) {
        return map("type", type, "value", value);
    }

    private static Map<String, Object> object(PaA paA, String id) {
        return l(paA.plan("detalle").get("objects")).stream().map(ContractFixtures::m)
                .filter(o -> id.equals(o.get("id"))).findFirst().orElseThrow();
    }

    private static void addFilter(PaA paA, String column, String operator, List<Map<String, Object>> values) {
        paA.operations("detalle").add(1, map("op", "FILTER", "predicates", list(map(
                "column", map("alias", "w", "column", column), "operator", operator,
                "values", new ArrayList<Object>(values)))));
    }

    /** Joins the well table (alias w) to a second table through w.field = t2.code, t2.code being its PK. */
    private static void addJoin(PaA paA) {
        Map<String, Object> plan = paA.plan("detalle");
        List<Object> objects = l(plan.get("objects"));
        objects.add(map("id", OTHER_TABLE, "type", "TABLE", "naturalKey", map("schema", "s", "name", "t2"),
                "uniqueKeys", list(map("kind", "PRIMARY_KEY", "columns", list(OTHER_PK)))));
        objects.add(map("id", OTHER_PK, "type", "COLUMN", "naturalKey", map("schema", "s", "name", "t2",
                "column", "code"), "parentId", OTHER_TABLE, "typeCategory", "TEXT", "nullable", false));
        objects.add(map("id", OTHER_LABEL, "type", "COLUMN", "naturalKey", map("schema", "s", "name", "t2",
                "column", "label"), "parentId", OTHER_TABLE, "typeCategory", "TEXT", "nullable", true));
        l(plan.get("relationships")).add(map("id", RELATIONSHIP, "origin", "SEMANTIC", "status", "APPROVED",
                "pairs", list(map("source", FIELD, "target", OTHER_PK)), "targetUniqueness", "PRIMARY_KEY",
                "allowedJoinTypes", list("LEFT")));
        paA.operations("detalle").add(1, map("op", "JOIN", "alias", "j", "from", "w", "relationship", RELATIONSHIP,
                "direction", "FORWARD", "type", "LEFT", "uniqueness", "PRIMARY_KEY",
                "conditions", list(map("left", map("alias", "w", "column", FIELD),
                        "right", map("alias", "j", "column", OTHER_PK)))));
    }

    private static Map<String, Object> join(PaA paA) {
        return m(paA.operations("detalle").get(1));
    }

    private static Map<String, Object> relationship(PaA paA) {
        return m(l(paA.plan("detalle").get("relationships")).get(0));
    }
}
