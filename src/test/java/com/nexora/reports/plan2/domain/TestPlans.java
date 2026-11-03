package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.ContractFixtures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test plans for the translator (A4-B3). Every plan goes through the B2 validator first ({@link #validated}): a test
 * never translates a plan that Reports would refuse. Besides the real PA-A documents of the contracts, it builds
 * plans of other shapes over other tables of the synthetic bench (companies and operators), and the same plans over
 * an invented schema, so that tests prove the translator holds no logic of a particular report or client.
 * <p>
 * In this package (test sources) only because {@code PlanV2Parser} is package-private.
 */
public final class TestPlans {

    public static final PlanV2Validator VALIDATOR = new PlanV2Validator(ContractFixtures.schemas(),
            ReportsCeilings.DEFAULTS, ContractFixtures.SHA256);

    public static final String BENCH = "wellnode_sintetico";
    public static final String CO = "c0000000-0000-4000-8000-000000000001";
    public static final String CO_ID = "c0000000-0000-4000-8000-000000000002";
    public static final String CO_NAME = "c0000000-0000-4000-8000-000000000003";
    public static final String CO_CODE = "c0000000-0000-4000-8000-000000000004";
    public static final String CO_PARENT = "c0000000-0000-4000-8000-000000000005";
    public static final String CO_ACTIVE = "c0000000-0000-4000-8000-000000000006";
    public static final String CO_OPERATOR = "c0000000-0000-4000-8000-000000000007";
    public static final String OP = "c0000000-0000-4000-8000-000000000011";
    public static final String OP_ID = "c0000000-0000-4000-8000-000000000012";
    public static final String OP_NAME = "c0000000-0000-4000-8000-000000000013";
    public static final String RELATIONSHIP = "c0000000-0000-4000-8000-000000000021";
    public static final String X = "c0000000-0000-4000-8000-000000000031";
    public static final String X_ID = "c0000000-0000-4000-8000-000000000032";
    public static final String X_VALUE = "c0000000-0000-4000-8000-000000000033";

    private TestPlans() {
    }

    /** The three datasets of the real PA-A report plan, validated by B2. */
    public static ValidatedReportPlan paA() {
        ContractFixtures.PaA paA = new ContractFixtures.PaA();
        return VALIDATOR.validate(paA.tenantId, paA.dataSourceId, paA.reportPlan, paA.spec, paA.plans);
    }

    /** Fails if B2 refuses the plan; otherwise its immutable model. */
    public static LogicalPlanV2 validated(Map<String, Object> tree) {
        List<PlanV2Violation> violations = VALIDATOR.checkLogicalPlan(tree, String.valueOf(tree.get("datasetId")));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("test plan refused by B2: " + violations);
        }
        return PlanV2Parser.logicalPlan(tree);
    }

    /** Active companies by operator: SCAN, LEFT JOIN, FILTER (BOOLEAN), AGGREGATE (COUNT), ORDER (C, NULLS LAST). */
    public static Map<String, Object> companiesByOperator(String schema) {
        Map<String, Object> plan = skeleton("empresas_por_operadora");
        plan.put("objects", list(
                table(CO, schema, "etp_company", CO_ID),
                column(CO_ID, CO, schema, "etp_company", "id", "NUMERIC", false),
                column(CO_ACTIVE, CO, schema, "etp_company", "is_active", "BOOLEAN", true),
                column(CO_OPERATOR, CO, schema, "etp_company", "id_operator", "NUMERIC", true),
                table(OP, schema, "bkw_operators", OP_ID),
                column(OP_ID, OP, schema, "bkw_operators", "id", "NUMERIC", false),
                column(OP_NAME, OP, schema, "bkw_operators", "name", "TEXT", false)));
        plan.put("relationships", list(map("id", RELATIONSHIP, "origin", "SEMANTIC", "status", "APPROVED",
                "pairs", list(map("source", CO_OPERATOR, "target", OP_ID)), "targetUniqueness", "PRIMARY_KEY",
                "allowedJoinTypes", list("INNER", "LEFT"))));
        plan.put("operations", list(
                map("op", "SCAN", "alias", "e", "object", CO),
                map("op", "JOIN", "alias", "o", "from", "e", "relationship", RELATIONSHIP, "direction", "FORWARD",
                        "type", "LEFT", "uniqueness", "PRIMARY_KEY",
                        "conditions", list(map("left", ref("e", CO_OPERATOR), "right", ref("o", OP_ID)))),
                map("op", "FILTER", "predicates", list(map("column", ref("e", CO_ACTIVE), "operator", "EQ",
                        "values", list(literal("BOOLEAN", "true"))))),
                map("op", "AGGREGATE", "groupBy", list(map("output", "operadora", "column", ref("o", OP_NAME))),
                        "measures", list(map("output", "empresas", "aggregation", "COUNT"))),
                map("op", "ORDER", "keys", list(map("output", "operadora", "direction", "ASC", "nulls", "LAST",
                        "collation", "C")), "tieBreaker", map("kind", "GROUP_KEYS", "outputs", list("operadora"))),
                map("op", "LIMIT", "maxRows", 100L, "detectTruncation", true)));
        plan.put("output", map("columns", list(output("operadora", "Operadora", "TEXT", "DIMENSION"),
                output("empresas", "Empresas", "INTEGER", "MEASURE"))));
        return plan;
    }

    /** Company detail: SCAN, FILTER (IN on text, IS_NOT_NULL), PROJECT, ORDER (DESC NULLS FIRST, unique key). */
    public static Map<String, Object> companyDetail(String schema) {
        Map<String, Object> plan = skeleton("detalle_empresas");
        plan.put("objects", list(
                table(CO, schema, "etp_company", CO_ID),
                column(CO_ID, CO, schema, "etp_company", "id", "NUMERIC", false),
                column(CO_NAME, CO, schema, "etp_company", "name", "TEXT", false),
                column(CO_CODE, CO, schema, "etp_company", "code", "TEXT", true),
                column(CO_PARENT, CO, schema, "etp_company", "parentid", "NUMERIC", true)));
        plan.put("relationships", list());
        plan.put("operations", list(
                map("op", "SCAN", "alias", "e", "object", CO),
                map("op", "FILTER", "predicates", list(
                        map("column", ref("e", CO_CODE), "operator", "IN", "values", list(literal("TEXT", "GPA"),
                                literal("TEXT", "SIC-FLU"), literal("TEXT", "GEA"), literal("TEXT", "GPA-SER"))),
                        map("column", ref("e", CO_NAME), "operator", "IS_NOT_NULL", "values", list()))),
                map("op", "PROJECT", "columns", list(map("output", "nombre", "column", ref("e", CO_NAME)),
                        map("output", "codigo", "column", ref("e", CO_CODE)),
                        map("output", "matriz", "column", ref("e", CO_PARENT)),
                        map("output", "row_key", "column", ref("e", CO_ID)))),
                map("op", "ORDER", "keys", list(map("output", "matriz", "direction", "DESC", "nulls", "FIRST"),
                        map("output", "row_key", "direction", "ASC", "nulls", "LAST")),
                        "tieBreaker", map("kind", "UNIQUE_KEY", "outputs", list("row_key"))),
                map("op", "LIMIT", "maxRows", 100L, "detectTruncation", true)));
        plan.put("output", map("columns", list(output("nombre", "Nombre", "TEXT", "DETAIL"),
                output("codigo", "Código", "TEXT", "DETAIL"), output("matriz", "Matriz", "INTEGER", "DETAIL"),
                output("row_key", "id", "INTEGER", "KEY", "hidden", true))));
        return plan;
    }

    /**
     * Detail of one column {@code value} of category {@code category} of table {@code schema.table}, filtered by
     * {@code operator} with the given literals, ordered by its primary key.
     */
    public static Map<String, Object> oneColumn(String schema, String table, String valueColumn, String category,
                                                String valueType, String operator, List<Object> literals) {
        Map<String, Object> plan = skeleton("una_columna");
        plan.put("objects", list(
                table(X, schema, table, X_ID),
                column(X_ID, X, schema, table, "id", "NUMERIC", false),
                column(X_VALUE, X, schema, table, valueColumn, category, true)));
        plan.put("relationships", list());
        plan.put("operations", list(
                map("op", "SCAN", "alias", "x", "object", X),
                map("op", "FILTER", "predicates", list(map("column", ref("x", X_VALUE), "operator", operator,
                        "values", new ArrayList<>(literals)))),
                map("op", "PROJECT", "columns", list(map("output", "valor", "column", ref("x", X_VALUE)),
                        map("output", "row_key", "column", ref("x", X_ID)))),
                map("op", "ORDER", "keys", list(map("output", "row_key", "direction", "ASC", "nulls", "LAST")),
                        "tieBreaker", map("kind", "UNIQUE_KEY", "outputs", list("row_key"))),
                map("op", "LIMIT", "maxRows", 10L, "detectTruncation", true)));
        plan.put("output", map("columns", list(output("valor", "Valor", valueType, "DETAIL"),
                output("row_key", "id", "INTEGER", "KEY", "hidden", true))));
        return plan;
    }

    public static Map<String, Object> literal(String type, String value) {
        return map("type", type, "value", value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> plan, String id) {
        return ((List<Object>) plan.get("objects")).stream().map(o -> (Map<String, Object>) o)
                .filter(o -> id.equals(o.get("id"))).findFirst().orElseThrow();
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public static List<Object> list(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    private static Map<String, Object> skeleton(String datasetId) {
        Map<String, Object> plan = map("format", "nexora-logical-plan/2",
                "tenantId", UUID.nameUUIDFromBytes("tenant".getBytes()).toString(),
                "dataSourceId", UUID.nameUUIDFromBytes("source".getBytes()).toString(),
                "metadataSnapshotId", UUID.nameUUIDFromBytes("snapshot".getBytes()).toString(),
                "specFingerprint", "a".repeat(64), "datasetId", datasetId);
        plan.put("definitions", list());
        plan.put("parameters", list());
        plan.put("fingerprint", "b".repeat(64));
        return plan;
    }

    private static Map<String, Object> table(String id, String schema, String name, String primaryKey) {
        return map("id", id, "type", "TABLE", "naturalKey", map("schema", schema, "name", name),
                "uniqueKeys", list(map("kind", "PRIMARY_KEY", "columns", list(primaryKey))));
    }

    private static Map<String, Object> column(String id, String parent, String schema, String table, String name,
                                              String category, boolean nullable) {
        return map("id", id, "type", "COLUMN", "naturalKey", map("schema", schema, "name", table, "column", name),
                "parentId", parent, "typeCategory", category, "nullable", nullable);
    }

    private static Map<String, Object> ref(String alias, String column) {
        return map("alias", alias, "column", column);
    }

    private static Map<String, Object> output(String name, String label, String valueType, String role,
                                              Object... extra) {
        Map<String, Object> output = map("output", name, "label", label, "valueType", valueType, "role", role,
                "origin", "INFERRED");
        for (int i = 0; i < extra.length; i += 2) {
            output.put((String) extra[i], extra[i + 1]);
        }
        return output;
    }
}
