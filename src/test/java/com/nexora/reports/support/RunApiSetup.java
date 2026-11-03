package com.nexora.reports.support;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.CanonicalJson;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;

/**
 * A4-B10: the platform of the {@code /2} API tests of A4-B10 (the same as {@code ReportRunApiIntegrationTest} of
 * A4-B9, which is left as it is): real JWT validation, the tenant's REPORTS_CLIENT service account, a fake Semantic
 * serving report plans as A3 serves them, the synthetic bench behind real TLS ({@code verify-full}) and Reports' own
 * database migrated by Flyway. Each test class uses its own tenant and data source, so classes never see each
 * other's runs or registrations.
 */
public final class RunApiSetup {

    /** Exactly the schemas the bench's read-only role can use (the tenancy declaration must match them). */
    public static final String BENCH_SCHEMAS = "{\"model\":\"DEDICATED_SCHEMAS\",\"schemas\":[\""
            + TlsBench.SCHEMA + "\",\"" + TlsBench.VOLUME_SCHEMA + "\"]}";

    private RunApiSetup() {
    }

    /** Reports' database, the identity provider, Semantic and the storage directory. */
    public static void common(DynamicPropertyRegistry registry, Path storage) {
        TlsBench.ensure();
        registry.add("spring.datasource.url", TestDatabase::reportsJdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.APP_ROLE);
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
        registry.add("spring.flyway.user", TestDatabase::superuserName);
        registry.add("spring.flyway.password", TestDatabase::superuserPassword);
        registry.add("spring.flyway.placeholders.appRole", () -> TestDatabase.APP_ROLE);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestPlatform::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestPlatform.REPORTS_AUDIENCE);
        registry.add("nexora.reports.semantic.base-url", TestPlatform::semanticUrl);
        registry.add("nexora.reports.semantic.token-uri", TestPlatform::tokenUri);
        registry.add("nexora.reports.enterprise.allowed-hosts[0]", TenantDatabase::host);
        registry.add("nexora.reports.enterprise.allowed-ports[0]", TenantDatabase::port);
        registry.add("nexora.reports.storage.directory", storage::toString);
    }

    /** The REPORTS_CLIENT service account of a tenant (index {@code i}). */
    public static void account(DynamicPropertyRegistry registry, int i, UUID tenant, String client, String secret) {
        String p = "nexora.reports.semantic.service-accounts[" + i + "].";
        registry.add(p + "tenant-id", tenant::toString);
        registry.add(p + "client-id", () -> client);
        registry.add(p + "client-secret", () -> secret);
        TestPlatform.registerClient(client, secret, tenant);
    }

    /** A bench data source of a tenant (index {@code i}), read with {@code verify-full} and at most 4 at a time. */
    public static void source(DynamicPropertyRegistry registry, int i, UUID tenant, UUID source) {
        String p = "nexora.reports.enterprise.sources[" + i + "].";
        registry.add(p + "tenant-id", tenant::toString);
        registry.add(p + "data-source-id", source::toString);
        registry.add(p + "host", TenantDatabase::host);
        registry.add(p + "port", TenantDatabase::port);
        registry.add(p + "database", () -> TlsBench.DATABASE);
        registry.add(p + "username", () -> TlsBench.READER);
        registry.add(p + "password", () -> TenantDatabase.PASSWORD);
        registry.add(p + "ssl-mode", () -> "verify-full");
        registry.add(p + "max-concurrent", () -> "4");
        registry.add(p + "ssl-root-cert", TenantDatabase::rootCert);
    }

    // ------------------------------------------------------------------ Semantic as A3 serves it

    public static void dataSource(UUID tenant, UUID source) {
        TestPlatform.semantic(tenant, "/api/v1/data-sources/" + source, 200, """
                {"id":"%s","key":"banco","name":"Banco sintético","engine":"postgresql","databaseName":"bench",
                 "description":null,"status":"ACTIVE","createdAt":"2026-11-01T10:00:00Z","createdBy":"x",
                 "updatedAt":"2026-11-01T10:00:00Z","updatedBy":"x","version":0}""".formatted(source));
    }

    public static String plan(UUID source, UUID reportPlan) {
        return "/api/v1/data-sources/" + source + "/report-plans/" + reportPlan;
    }

    /** Serves the documents of a report plan; its validity is served apart ({@link #validity}). */
    public static void serve(UUID tenant, UUID source, UUID reportPlan, ContractFixtures.PaA docs) {
        String base = plan(source, reportPlan);
        TestPlatform.semantic(tenant, base, 200, CanonicalJson.write(docs.reportPlan));
        TestPlatform.semantic(tenant, base + "/spec", 200, CanonicalJson.write(docs.spec));
        docs.plans.forEach((id, p) -> TestPlatform.semantic(tenant, base + "/logical-plans/" + id, 200,
                CanonicalJson.write(p)));
    }

    public static TestPlatform.Response validity(UUID reportPlan, String status) {
        String issues = "STALE".equals(status)
                ? "[{\"code\":\"OBJECT_CHANGED\",\"kind\":\"PHYSICAL_OBJECT\",\"referenceId\":null}]" : "[]";
        return new TestPlatform.Response(200, "{\"reportPlanId\":\"" + reportPlan + "\",\"status\":\"" + status
                + "\",\"issues\":" + issues + "}");
    }

    /** Validity answered with {@code responses} in order (before reading, after reading). */
    public static void validities(UUID tenant, UUID source, UUID reportPlan, TestPlatform.Response... responses) {
        TestPlatform.semanticSequence(tenant, plan(source, reportPlan) + "/validity", responses);
    }

    /** The real PA-A documents for another tenant and data source, resealed as Semantic would compile them. */
    public static ContractFixtures.PaA paA(UUID tenant, UUID source) {
        ContractFixtures.PaA docs = new ContractFixtures.PaA();
        docs.tenantId = tenant;
        docs.dataSourceId = source;
        docs.reportPlan.put("tenantId", tenant.toString());
        docs.reportPlan.put("dataSourceId", source.toString());
        docs.plans.values().forEach(p -> {
            p.put("tenantId", tenant.toString());
            p.put("dataSourceId", source.toString());
        });
        docs.reseal();
        return docs;
    }

    /** The budget of a report plan (a plan may ask for less than the ceilings, never for more). */
    public static Map<String, Object> budget(ContractFixtures.PaA docs) {
        return m(docs.reportPlan.get("budget"));
    }

    /** The LIMIT of the plan of a dataset. */
    public static void limit(ContractFixtures.PaA docs, String datasetId, long maxRows) {
        m(docs.operations(datasetId).getLast()).put("maxRows", maxRows);
    }

    /**
     * Adds {@code count} joins to the detail plan: the well table (alias w) joined to made-up tables through
     * w.field = tn.code, tn.code being their primary key (the same construction as the validator tests).
     */
    public static void joins(ContractFixtures.PaA docs, int count) {
        String field = "7a3f0000-0000-4000-8000-000000000013";
        Map<String, Object> plan = docs.plan("detalle");
        for (int n = 1; n <= count; n++) {
            String table = "7a3f0000-0000-4000-8000-000000000" + (100 + n * 10);
            String pk = "7a3f0000-0000-4000-8000-000000000" + (101 + n * 10);
            String relationship = "7a3f0000-0000-4000-8000-000000000" + (102 + n * 10);
            l(plan.get("objects")).add(new java.util.LinkedHashMap<>(Map.of("id", table, "type", "TABLE",
                    "naturalKey", Map.of("schema", "s", "name", "t" + n),
                    "uniqueKeys", List.of(Map.of("kind", "PRIMARY_KEY", "columns", List.of(pk))))));
            l(plan.get("objects")).add(new java.util.LinkedHashMap<>(Map.of("id", pk, "type", "COLUMN",
                    "naturalKey", Map.of("schema", "s", "name", "t" + n, "column", "code"), "parentId", table,
                    "typeCategory", "TEXT", "nullable", false)));
            l(plan.get("relationships")).add(new java.util.LinkedHashMap<>(Map.of("id", relationship,
                    "origin", "SEMANTIC", "status", "APPROVED", "pairs", List.of(Map.of("source", field,
                            "target", pk)), "targetUniqueness", "PRIMARY_KEY", "allowedJoinTypes", List.of("LEFT"))));
            Map<String, Object> join = new java.util.LinkedHashMap<>();
            join.put("op", "JOIN");
            join.put("alias", "j" + n);
            join.put("from", "w");
            join.put("relationship", relationship);
            join.put("direction", "FORWARD");
            join.put("type", "LEFT");
            join.put("uniqueness", "PRIMARY_KEY");
            join.put("conditions", List.of(Map.of("left", Map.of("alias", "w", "column", field),
                    "right", Map.of("alias", "j" + n, "column", pk))));
            docs.operations("detalle").add(1, join);
        }
    }

    // ------------------------------------------------------------------ tokens

    public static String token(UUID tenant, String subject, String type, String... roles) {
        return TestPlatform.userTokenWithClaims(tenant, type, Map.of("sub", subject), roles);
    }

    // ------------------------------------------------------------------ Reports' database, as superuser

    /** Number of runs recorded for a tenant. */
    public static int runs(UUID tenant) throws SQLException {
        return integer("SELECT count(*) FROM reports.report_run WHERE tenant_id = '" + tenant + "'");
    }

    public static List<String> events(UUID runId) throws SQLException {
        return strings("SELECT kind FROM reports.report_run_event WHERE run_id = '" + runId
                + "' ORDER BY recorded_at, kind");
    }

    public static String outcome(UUID runId) throws SQLException {
        List<String> kinds = strings("SELECT kind FROM reports.report_run_outcome WHERE run_id = '" + runId + "'");
        return kinds.isEmpty() ? null : kinds.getFirst();
    }

    /**
     * Everything Reports stored about the runs of a tenant (runs, outcomes, artifact metadata and events, never the
     * file itself), as JSON text: what a leak test searches.
     */
    public static String storedAbout(UUID tenant) throws SQLException {
        String where = " WHERE tenant_id = '" + tenant + "'";
        List<String> rows = new ArrayList<>();
        for (String table : List.of("report_run", "report_run_outcome", "report_artifact", "report_run_event")) {
            rows.addAll(strings("SELECT row_to_json(t)::text FROM reports." + table + " t" + where));
        }
        return String.join("\n", rows);
    }

    /** The keys used inside the {@code datasets} and {@code warnings} of the outcomes of a tenant. */
    public static List<String> outcomeKeys(UUID tenant, String column) throws SQLException {
        return strings("SELECT DISTINCT jsonb_object_keys(e) FROM reports.report_run_outcome o,"
                + " jsonb_array_elements(o." + column + ") e WHERE o.tenant_id = '" + tenant + "' ORDER BY 1");
    }

    private static int integer(String sql) throws SQLException {
        return Integer.parseInt(strings(sql).getFirst());
    }

    private static List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /** The names of the wells of PA-A in {@code esperado/pa-a.json}: business values a leak test searches. */
    public static List<String> wellNames() {
        Map<String, Object> expected = ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
        List<String> names = new ArrayList<>();
        for (Object row : l(expected.get("detalle"))) {
            names.add((String) m(row).get("nombre"));
        }
        return names;
    }
}
