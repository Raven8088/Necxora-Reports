package com.nexora.reports.result;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.domain.TestPlans.CO;
import static com.nexora.reports.plan2.domain.TestPlans.CO_ACTIVE;
import static com.nexora.reports.plan2.domain.TestPlans.CO_CODE;
import static com.nexora.reports.plan2.domain.TestPlans.CO_ID;
import static com.nexora.reports.plan2.domain.TestPlans.CO_NAME;
import static com.nexora.reports.plan2.domain.TestPlans.CO_PARENT;
import static com.nexora.reports.plan2.domain.TestPlans.OP_NAME;
import static com.nexora.reports.plan2.domain.TestPlans.RELATIONSHIP;
import static com.nexora.reports.plan2.domain.TestPlans.list;
import static com.nexora.reports.plan2.domain.TestPlans.literal;
import static com.nexora.reports.plan2.domain.TestPlans.map;

/**
 * A4-B6 (D-B6-7): complete, sealed documents of a report that is structurally different from PA-A, built here and
 * validated by B2 exactly like documents from Semantic. It proves that results are assembled by the same generic
 * code for any report: «Empresas activas por operadora» has a JOIN through an approved relationship, filters
 * (BOOLEAN, IN on text, IS_NOT_NULL), a summary with its own total dataset and a detail with a descending order,
 * nulls first, and a hidden key, over other tables of the synthetic bench (or of any schema with the same shape).
 */
public final class ReportFixtures {

    private ReportFixtures() {
    }

    /** The documents of the second report, mutable and resealable like {@link ContractFixtures.PaA}. */
    public static final class Empresas {

        public static final UUID PLAN_SUMMARY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
        public static final UUID PLAN_TOTAL = UUID.fromString("e0000000-0000-4000-8000-000000000002");
        public static final UUID PLAN_DETAIL = UUID.fromString("e0000000-0000-4000-8000-000000000003");

        public final UUID tenantId = UUID.nameUUIDFromBytes("tenant".getBytes());
        public final UUID dataSourceId = UUID.nameUUIDFromBytes("source".getBytes());
        public final Map<String, Object> spec;
        public final Map<String, Object> reportPlan;
        public final Map<UUID, Map<String, Object>> plans = new LinkedHashMap<>();

        public Empresas(String schema) {
            Map<String, Object> summary = TestPlans.companiesByOperator(schema);
            summary.put("datasetId", "por_operadora");
            Map<String, Object> detail = TestPlans.companyDetail(schema);
            detail.put("datasetId", "detalle_empresas");
            plans.put(PLAN_SUMMARY, summary);
            plans.put(PLAN_TOTAL, totalPlan(schema));
            plans.put(PLAN_DETAIL, detail);
            spec = spec();
            reportPlan = map("format", "nexora-report-plan/1", "tenantId", tenantId.toString(),
                    "dataSourceId", dataSourceId.toString(), "profile", "basic-a", "specFingerprint", "",
                    "plans", list(ref("por_operadora", PLAN_SUMMARY), ref("total_empresas", PLAN_TOTAL),
                            ref("detalle_empresas", PLAN_DETAIL)),
                    "consistency", map("mode", "SINGLE_READ_ONLY_TRANSACTION", "isolation", "REPEATABLE_READ",
                            "readOnly", true, "schemaVerification", "IN_TRANSACTION", "onPlanFailure", "REFUSE_RUN"),
                    "scope", map("rules", list()), "compositions", list(),
                    "budget", map("maxPlans", 3L, "maxRowsPerPlan", 1000L, "maxTotalRows", 3000L,
                            "statementTimeoutMs", 30000L, "maxDurationMs", 60000L),
                    "fingerprint", "");
            reseal();
        }

        public ValidatedReportPlan validate() {
            return TestPlans.VALIDATOR.validate(tenantId, dataSourceId, reportPlan, spec, plans);
        }

        public Map<String, Object> plan(String datasetId) {
            return plans.values().stream().filter(p -> datasetId.equals(p.get("datasetId"))).findFirst()
                    .orElseThrow();
        }

        /** Recomputes every fingerprint after a change, as Semantic would when compiling the documents. */
        public void reseal() {
            String specFingerprint = CanonicalJson.sha256(spec, ContractFixtures.SHA256);
            reportPlan.put("specFingerprint", specFingerprint);
            for (Object o : ContractFixtures.l(reportPlan.get("plans"))) {
                Map<String, Object> ref = ContractFixtures.m(o);
                Map<String, Object> plan = plans.get(UUID.fromString((String) ref.get("planId")));
                plan.put("tenantId", tenantId.toString());
                plan.put("dataSourceId", dataSourceId.toString());
                plan.put("specFingerprint", specFingerprint);
                plan.put("fingerprint", CanonicalJson.fingerprintOf(plan, ContractFixtures.SHA256));
                ref.put("planFingerprint", plan.get("fingerprint"));
            }
            reportPlan.put("fingerprint", CanonicalJson.fingerprintOf(reportPlan, ContractFixtures.SHA256));
        }

        private static Map<String, Object> ref(String datasetId, UUID planId) {
            return map("datasetId", datasetId, "planId", planId.toString(), "planFingerprint", "");
        }

        /** COUNT of active companies, without groups: the total of the summary, from its own query. */
        private static Map<String, Object> totalPlan(String schema) {
            Map<String, Object> plan = TestPlans.companiesByOperator(schema);
            plan.put("datasetId", "total_empresas");
            plan.put("objects", list(
                    map("id", CO, "type", "TABLE", "naturalKey", map("schema", schema, "name", "etp_company"),
                            "uniqueKeys", list(map("kind", "PRIMARY_KEY", "columns", list(CO_ID)))),
                    map("id", CO_ID, "type", "COLUMN", "naturalKey", map("schema", schema, "name", "etp_company",
                            "column", "id"), "parentId", CO, "typeCategory", "NUMERIC", "nullable", false),
                    map("id", CO_ACTIVE, "type", "COLUMN", "naturalKey", map("schema", schema,
                            "name", "etp_company", "column", "is_active"), "parentId", CO,
                            "typeCategory", "BOOLEAN", "nullable", true)));
            plan.put("relationships", list());
            plan.put("operations", list(
                    map("op", "SCAN", "alias", "e", "object", CO),
                    map("op", "FILTER", "predicates", list(map("column", map("alias", "e", "column", CO_ACTIVE),
                            "operator", "EQ", "values", list(literal("BOOLEAN", "true"))))),
                    map("op", "AGGREGATE", "groupBy", list(),
                            "measures", list(map("output", "empresas", "aggregation", "COUNT"))),
                    map("op", "ORDER", "keys", list(), "tieBreaker", map("kind", "SINGLE_ROW", "outputs", list())),
                    map("op", "LIMIT", "maxRows", 100L, "detectTruncation", true)));
            plan.put("output", map("columns", list(map("output", "empresas", "label", "Total de empresas",
                    "valueType", "INTEGER", "role", "MEASURE", "origin", "INFERRED"))));
            return plan;
        }

        private static Map<String, Object> spec() {
            Map<String, Object> active = map("column", map("alias", "e", "object", CO_ACTIVE), "operator", "EQ",
                    "values", list(map("type", "BOOLEAN", "value", "true")), "literalSource", "QUESTION");
            return map("format", "nexora-report-spec/1", "profile", "basic-a", "status", "READY",
                    "title", "Empresas activas por operadora",
                    "request", map("text", "Cuántas empresas activas tiene cada operadora, con el total, y el "
                            + "listado de las empresas GPA, SIC-FLU, GEA y GPA-SER con su matriz.", "language", "es"),
                    "parameters", map("timeZone", "America/Bogota", "labelLanguage", "es"),
                    "datasets", list(
                            map("id", "por_operadora", "title", "Empresas activas por operadora",
                                    "mode", "AGGREGATE", "base", map("alias", "e", "object", CO),
                                    "joins", list(map("alias", "o", "from", "e",
                                            "via", map("relationship", RELATIONSHIP))),
                                    "select", list(
                                            map("id", "operadora", "label", "Operadora", "origin", "INFERRED",
                                                    "column", map("alias", "o", "object", OP_NAME)),
                                            map("id", "empresas", "label", "Empresas", "origin", "INFERRED",
                                                    "measure", map("aggregation", "COUNT"))),
                                    "groupBy", list("operadora"), "filters", list(active),
                                    "orderBy", list(map("select", "operadora", "direction", "ASC", "nulls", "LAST",
                                            "collation", "C"))),
                            map("id", "total_empresas", "title", "Total de empresas activas",
                                    "mode", "AGGREGATE", "base", map("alias", "e", "object", CO), "joins", list(),
                                    "select", list(map("id", "empresas", "label", "Total de empresas",
                                            "origin", "INFERRED", "measure", map("aggregation", "COUNT"))),
                                    "groupBy", list(), "filters", list(ContractFixtures.copy(active)),
                                    "orderBy", list()),
                            map("id", "detalle_empresas", "title", "Detalle de empresas", "mode", "DETAIL",
                                    "base", map("alias", "e", "object", CO), "joins", list(),
                                    "select", list(
                                            map("id", "nombre", "label", "Nombre", "origin", "INFERRED",
                                                    "column", map("alias", "e", "object", CO_NAME)),
                                            map("id", "codigo", "label", "Código", "origin", "INFERRED",
                                                    "column", map("alias", "e", "object", CO_CODE)),
                                            map("id", "matriz", "label", "Matriz", "origin", "INFERRED",
                                                    "column", map("alias", "e", "object", CO_PARENT))),
                                    "groupBy", list(),
                                    "filters", list(
                                            map("column", map("alias", "e", "object", CO_CODE), "operator", "IN",
                                                    "values", list(map("type", "TEXT", "value", "GPA"),
                                                            map("type", "TEXT", "value", "SIC-FLU"),
                                                            map("type", "TEXT", "value", "GEA"),
                                                            map("type", "TEXT", "value", "GPA-SER")),
                                                    "literalSource", "QUESTION"),
                                            map("column", map("alias", "e", "object", CO_NAME),
                                                    "operator", "IS_NOT_NULL", "values", list(),
                                                    "literalSource", "QUESTION")),
                                    "orderBy", list(map("select", "matriz", "direction", "DESC",
                                            "nulls", "FIRST")))),
                    "sections", list(map("id", "empresas", "title", "Empresas por operadora", "items", list(
                            map("type", "summaryTable", "dataset", "por_operadora", "total", "total_empresas"),
                            map("type", "table", "dataset", "detalle_empresas")))),
                    "compositions", list(),
                    "provenance", map("terms", list(
                            map("text", "empresas activas", "resolution", "INFERRED",
                                    "target", "etp_company.is_active"),
                            map("text", "operadora", "resolution", "INFERRED", "target", "bkw_operators.name"))),
                    "clarifications", list());
        }
    }
}
