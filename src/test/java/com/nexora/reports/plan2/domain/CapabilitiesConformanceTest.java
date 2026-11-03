package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.ContractFixtures.PaA;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B10 (D-A4-8, D-B10-4; test T-10, additional to T-1…T-8 of the audit): the {@code nexora-capabilities/1} of
 * Reports is the contract document, is generated from the validator's own constants and ceilings, and every value
 * it announces is accepted while every value of the contract it does not announce is refused (by the schema of the
 * specification, by the profile rules of the logical plans or by the ceilings). Item types and
 * compositions, enforced when the result is assembled, are checked in {@code CapabilitiesItemConformanceTest}.
 * <p>
 * Positive cases start from the real PA-A documents; negative cases mutate copies and recompute every fingerprint,
 * so a refusal is the rule under test and not a fingerprint.
 */
class CapabilitiesConformanceTest {

    private static final PlanV2Validator VALIDATOR = new PlanV2Validator(ContractFixtures.schemas(),
            ReportsCeilings.DEFAULTS, ContractFixtures.SHA256);
    private static final ContractSchema SCHEMA = RunContractSchemas.capabilities(ContractFixtures.READER);

    private static final String DBID = "7a3f0000-0000-4000-8000-000000000011";
    private static final String FIELD = "7a3f0000-0000-4000-8000-000000000013";
    private static final String STATUS = "7a3f0000-0000-4000-8000-000000000016";

    // ------------------------------------------------------------------ the document

    @Test
    void theSchemaUsedInProductionIsTheContractSchemaByteForByte() throws Exception {
        String manifest = new String(ContractFixtures.bytes(ContractFixtures.ROOT + "CAPABILITIES-CONTRACT.sha256"),
                StandardCharsets.UTF_8);
        List<String> lines = manifest.lines().filter(x -> !x.isBlank()).toList();
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            String[] parts = line.split("\\s+", 2);
            assertThat(sha256(ContractFixtures.bytes(ContractFixtures.ROOT + parts[1]))).as(parts[1])
                    .isEqualTo(parts[0]);
        }
        assertThat(sha256(ContractFixtures.bytes(RunContractSchemas.CAPABILITIES)))
                .isEqualTo("182d2413be8c6bfdc94beb642f13805e08dcb98de280c683b3169321b48daa72");
    }

    @Test
    void theDescriptorSatisfiesTheContractAndSoDoesTheExampleOfSemantic() {
        Map<String, Object> semantic = ContractFixtures.read("examples/valid/capabilities.semantic.basic-a.json");

        assertThat(SCHEMA.firstError(ReportsCapabilities.of("0.1.0", ReportsCeilings.DEFAULTS).document()))
                .isEmpty();
        assertThat(SCHEMA.firstError(semantic)).isEmpty();
        Map<String, Object> broken = new LinkedHashMap<>(ReportsCapabilities.of("0.1.0", ReportsCeilings.DEFAULTS)
                .document());
        broken.put("service", "intelligence");
        assertThat(SCHEMA.firstError(broken)).isPresent();
    }

    @Test
    void theDescriptorIsGeneratedFromTheProfileAndTheCeilingsOfTheValidator() {
        Map<String, Object> doc = ReportsCapabilities.of("0.1.0-test", ReportsCeilings.DEFAULTS).document();

        assertThat(doc.get("format")).isEqualTo("nexora-capabilities/1");
        assertThat(doc.get("service")).isEqualTo("reports");
        assertThat(doc.get("serviceVersion")).isEqualTo("0.1.0-test");
        // Exactly the list of the A4 proposal v3, section 5 (B10-b.1: report-artifact/1 is not added).
        assertThat(doc.get("contracts")).isEqualTo(List.of("nexora-report-plan/1", "nexora-logical-plan/2",
                "nexora-logical-plan/1", "nexora-report-run/1", "nexora-excel-report/1"));
        List<Object> profiles = l(doc.get("profiles"));
        assertThat(profiles).hasSize(1);
        Map<String, Object> basicA = m(profiles.getFirst());
        assertThat(basicA.get("name")).isEqualTo("basic-a");
        assertThat(basicA.get("maxDatasets")).isEqualTo(3);
        assertThat(basicA.get("maxSections")).isEqualTo(1);
        assertThat(basicA.get("maxJoinsPerDataset")).isEqualTo(2);
        assertThat(basicA.get("aggregations")).isEqualTo(List.of("COUNT"));
        assertThat(basicA.get("itemTypes")).isEqualTo(List.of("table", "summaryTable"));
        assertThat(basicA.get("compositions")).isEqualTo(List.of());
        assertThat(basicA.get("planOperations")).isEqualTo(List.of("SCAN", "JOIN", "FILTER", "AGGREGATE",
                "PROJECT", "ORDER", "LIMIT"));
        assertThat(basicA.get("maxRowsPerPlan")).isEqualTo(10_000);
    }

    @Test
    void theRowLimitAnnouncedIsTheEffectiveCeilingOfTheValidator() {
        ReportsCeilings narrow = new ReportsCeilings(2, 500, 1_000, 64, 30_000, 60_000);

        Map<String, Object> basicA = m(l(ReportsCapabilities.of("x", narrow).document().get("profiles"))
                .getFirst());

        assertThat(basicA.get("maxRowsPerPlan")).isEqualTo(500);
        assertThat(basicA.get("maxDatasets")).isEqualTo(2);
    }

    /**
     * Intelligence uses the intersection of what Semantic and Reports announce (capabilities/1 README): today the
     * profile of Reports announces exactly what the published example of Semantic announces.
     */
    @Test
    void basicAOfReportsEqualsBasicAOfTheSemanticExample() {
        Map<String, Object> semantic = m(l(ContractFixtures.read("examples/valid/capabilities.semantic.basic-a.json")
                .get("profiles")).getFirst());
        Map<String, Object> reports = m(l(ReportsCapabilities.of("x", ReportsCeilings.DEFAULTS).document()
                .get("profiles")).getFirst());

        for (String key : semantic.keySet()) {
            Object expected = semantic.get(key) instanceof Number n ? (Object) n.intValue() : semantic.get(key);
            Object actual = reports.get(key) instanceof Number n ? (Object) n.intValue() : reports.get(key);
            assertThat(actual).as(key).isEqualTo(expected);
        }
        assertThat(reports.keySet()).isEqualTo(semantic.keySet());
    }

    // ------------------------------------------------------------------ announced values are accepted

    @Test
    void everyAnnouncedPlanOperationIsAccepted() {
        PaA paA = new PaA();
        addJoin(paA, 1);
        addFilter(paA, STATUS, "IN", List.of(literal("TEXT", "Activo")));
        paA.reseal();

        ValidatedReportPlan plan = accepted(paA);

        List<String> used = new ArrayList<>();
        plan.datasets().forEach(d -> d.plan().operations().forEach(op -> used.add(name(op))));
        assertThat(used).containsAll(ReportsProfile.PLAN_OPERATIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "IS_NULL", "IS_NOT_NULL"})
    void everyAnnouncedFilterOperatorIsAccepted(String operator) {
        assertThat(ReportsProfile.FILTER_OPERATORS).contains(operator);
        PaA paA = new PaA();
        switch (operator) {
            case "GT", "GTE", "LT", "LTE" -> addFilter(paA, DBID, operator, List.of(literal("NUMBER", "10")));
            case "IS_NULL", "IS_NOT_NULL" -> addFilter(paA, STATUS, operator, List.of());
            default -> addFilter(paA, STATUS, operator, List.of(literal("TEXT", "Ñandú")));
        }
        paA.reseal();

        accepted(paA);
    }

    @Test
    void twoJoinsPerDatasetAreAcceptedInTheSpecificationAndInThePlan() {
        PaA paA = new PaA();
        addJoin(paA, 1);
        addJoin(paA, 2);
        specJoins(paA.spec, 2);
        paA.reseal();

        accepted(paA);
    }

    /**
     * For {@code basic-a} the schema of {@code nexora-report-spec/1} itself limits datasets, joins, sections, item
     * types, aggregations and compositions: those limits are the ones announced (and the ones the plan ceilings and
     * {@link BasicAProfile} apply to the logical plans).
     */
    @Test
    void theLimitsOfBasicAInTheSchemaOfTheSpecificationAreTheAnnouncedOnes() {
        Map<String, Object> schema = ContractFixtures.read("report-spec/1/nexora-report-spec-1.schema.json");
        Map<String, Object> basicA = null;
        for (Object rule : l(schema.get("allOf"))) {
            Map<String, Object> condition = m(m(m(rule).get("if")).get("properties"));
            if (condition.containsKey("profile") && "basic-a".equals(m(condition.get("profile")).get("const"))) {
                basicA = m(m(m(rule).get("then")).get("properties"));
            }
        }
        assertThat(basicA).isNotNull();
        Map<String, Object> datasets = m(basicA.get("datasets"));
        Map<String, Object> dataset = m(m(datasets.get("items")).get("properties"));
        Map<String, Object> sections = m(basicA.get("sections"));
        Map<String, Object> item = m(m(m(m(m(sections.get("items")).get("properties")).get("items")).get("items"))
                .get("properties"));
        Map<String, Object> measure = m(m(m(m(m(dataset.get("select")).get("items")).get("properties"))
                .get("measure")).get("properties"));

        assertThat(number(datasets.get("maxItems"))).isEqualTo(ReportsCeilings.DEFAULTS.maxPlans());
        assertThat(number(m(dataset.get("joins")).get("maxItems"))).isEqualTo(ReportsProfile.MAX_JOINS_PER_DATASET);
        assertThat(number(sections.get("maxItems"))).isEqualTo(ReportsProfile.MAX_SECTIONS);
        assertThat(m(item.get("type")).get("enum")).isEqualTo(ReportsProfile.ITEM_TYPES);
        assertThat(m(measure.get("aggregation")).get("enum")).isEqualTo(ReportsProfile.AGGREGATIONS);
        assertThat(number(m(basicA.get("compositions")).get("maxItems"))).isEqualTo(ReportsProfile.COMPOSITIONS.size());
    }

    // ------------------------------------------------------------------ what is not announced is refused

    @ParameterizedTest
    @ValueSource(strings = {"COUNT_DISTINCT", "SUM", "AVG", "MIN", "MAX"})
    void everyAggregationThatIsNotAnnouncedIsRefused(String aggregation) {
        assertThat(ReportsProfile.AGGREGATIONS).doesNotContain(aggregation);

        refused("BASIC-A", p -> {
            Map<String, Object> measure = m(l(m(p.operations("total").get(1)).get("measures")).get(0));
            measure.put("aggregation", aggregation);
            measure.put("column", map("alias", "w", "column", DBID));
        });
    }

    @Test
    void anOperationThatIsNotAnnouncedIsRefused() {
        assertThat(ReportsProfile.PLAN_OPERATIONS).doesNotContain("GROUP_FILTER");

        refused("BASIC-A", p -> p.operations("resumen").add(2, map("op", "GROUP_FILTER",
                "predicates", list(map("output", "pozos", "operator", "GT",
                        "values", list(literal("NUMBER", "1")))))));
    }

    @Test
    void aTextKeyWithoutTheAnnouncedCollationIsRefused() {
        refused("BASIC-A", p -> m(l(m(p.operations("detalle").get(2)).get("keys")).get(0)).remove("collation"));
    }

    @Test
    void aUniquenessThatIsNotAnnouncedIsRefused() {
        assertThat(ReportsProfile.UNIQUENESS).doesNotContain("UNIQUE_CONSTRAINT");

        refused("BASIC-A", p -> {
            addJoin(p, 1);
            m(l(p.plan("detalle").get("relationships")).get(0)).put("targetUniqueness", "UNIQUE_CONSTRAINT");
        });
    }

    @Test
    void moreDatasetsThanAnnouncedAreRefused() {
        refused("BUDGET", p -> m(p.reportPlan.get("budget")).put("maxPlans", 4L));
    }

    @Test
    void moreRowsPerPlanThanAnnouncedAreRefused() {
        refused("BUDGET", p -> m(p.reportPlan.get("budget")).put("maxRowsPerPlan", 10_001L));
    }

    /** Enforced by the schema of the specification (contract_invalid), before any profile rule. */
    @Test
    void moreSectionsThanAnnouncedAreRefused() {
        PlanV2Exception e = refused("SCHEMA", p -> l(p.spec.get("sections")).add(map("id", "otra",
                "title", "Otra sección", "items", list(map("type", "table", "dataset", "detalle")))));

        assertThat(e.violations()).contains(new PlanV2Violation("SCHEMA", PlanV2Violation.Kind.CONTRACT_INVALID,
                "spec.sections"));
    }

    /** Enforced by the schema of the specification (contract_invalid), before any profile rule. */
    @Test
    void moreJoinsInADatasetOfTheSpecificationThanAnnouncedAreRefused() {
        PlanV2Exception e = refused("SCHEMA", p -> specJoins(p.spec, 3));

        assertThat(e.violations()).contains(new PlanV2Violation("SCHEMA", PlanV2Violation.Kind.CONTRACT_INVALID,
                "spec.datasets[2].joins"));
    }

    /** New in A4-B10 (D-B10-4): a logical plan with more JOIN operations than announced is refused. */
    @Test
    void moreJoinsInALogicalPlanThanAnnouncedAreRefused() {
        PlanV2Exception e = refused("BASIC-A", p -> {
            addJoin(p, 1);
            addJoin(p, 2);
            addJoin(p, 3);
        });

        assertThat(e.violations()).contains(new PlanV2Violation("BASIC-A", PlanV2Violation.Kind.PLAN_NOT_SUPPORTED,
                "logical-plan[detalle].joins"));
    }

    @Test
    void aProfileThatIsNotAnnouncedIsRefused() {
        refused("BASIC-A", p -> p.reportPlan.put("profile", "basic-b"));
    }

    // ------------------------------------------------------------------ helpers

    private static ValidatedReportPlan accepted(PaA paA) {
        return VALIDATOR.validate(paA.tenantId, paA.dataSourceId, paA.reportPlan, paA.spec, paA.plans);
    }

    private static PlanV2Exception refused(String rule, Consumer<PaA> mutation) {
        PaA paA = new PaA();
        mutation.accept(paA);
        paA.reseal();
        Throwable thrown = catchThrowable(() -> accepted(paA));
        assertThat(thrown).isInstanceOf(PlanV2Exception.class);
        PlanV2Exception e = (PlanV2Exception) thrown;
        assertThat(e.violations().stream().map(PlanV2Violation::rule).toList()).contains(rule);
        return e;
    }

    private static String name(LogicalPlanV2.Operation op) {
        return switch (op) {
            case LogicalPlanV2.Scan s -> "SCAN";
            case LogicalPlanV2.Join j -> "JOIN";
            case LogicalPlanV2.Filter f -> "FILTER";
            case LogicalPlanV2.Aggregate a -> "AGGREGATE";
            case LogicalPlanV2.Project p -> "PROJECT";
            case LogicalPlanV2.Order o -> "ORDER";
            case LogicalPlanV2.Limit l -> "LIMIT";
        };
    }

    /** Gives the detail dataset of the specification {@code count} joins through (made-up) relationships. */
    private static void specJoins(Map<String, Object> spec, int count) {
        Map<String, Object> detail = m(l(spec.get("datasets")).get(2));
        List<Object> joins = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            joins.add(map("alias", "j" + i, "from", "w", "via",
                    map("relationship", "7a3f0000-0000-4000-8000-0000000000b" + i)));
        }
        detail.put("joins", joins);
    }

    /** Adds a FILTER right after the SCAN and the JOINs of the detail plan (the order PLAN-R1 requires). */
    private static void addFilter(PaA paA, String column, String operator, List<Map<String, Object>> values) {
        int at = 1 + (int) paA.operations("detalle").stream().filter(op -> "JOIN".equals(m(op).get("op"))).count();
        paA.operations("detalle").add(at, map("op", "FILTER", "predicates", list(map(
                "column", map("alias", "w", "column", column), "operator", operator,
                "values", new ArrayList<Object>(values)))));
    }

    /** Joins the well table (alias w) to table {@code n} through w.field = tn.code, tn.code being its PK. */
    private static void addJoin(PaA paA, int n) {
        String id = "7a3f0000-0000-4000-8000-000000000" + (100 + n * 10);
        String pk = "7a3f0000-0000-4000-8000-000000000" + (101 + n * 10);
        String relationship = "7a3f0000-0000-4000-8000-000000000" + (102 + n * 10);
        Map<String, Object> plan = paA.plan("detalle");
        List<Object> objects = l(plan.get("objects"));
        objects.add(map("id", id, "type", "TABLE", "naturalKey", map("schema", "s", "name", "t" + n),
                "uniqueKeys", list(map("kind", "PRIMARY_KEY", "columns", list(pk)))));
        objects.add(map("id", pk, "type", "COLUMN", "naturalKey", map("schema", "s", "name", "t" + n,
                "column", "code"), "parentId", id, "typeCategory", "TEXT", "nullable", false));
        l(plan.get("relationships")).add(map("id", relationship, "origin", "SEMANTIC", "status", "APPROVED",
                "pairs", list(map("source", FIELD, "target", pk)), "targetUniqueness", "PRIMARY_KEY",
                "allowedJoinTypes", list("LEFT")));
        paA.operations("detalle").add(1, map("op", "JOIN", "alias", "j" + n, "from", "w",
                "relationship", relationship, "direction", "FORWARD", "type", "LEFT", "uniqueness", "PRIMARY_KEY",
                "conditions", list(map("left", map("alias", "w", "column", FIELD),
                        "right", map("alias", "j" + n, "column", pk)))));
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

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
