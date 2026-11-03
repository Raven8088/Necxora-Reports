package com.nexora.reports.api;

import com.nexora.reports.config.RunHousekeeping;
import com.nexora.reports.execution.application.ReportRunDocuments;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.result.ReportFixtures;
import com.nexora.reports.support.TenantDatabase;
import com.nexora.reports.support.TestDatabase;
import com.nexora.reports.support.TestPlatform;
import com.nexora.reports.support.TlsBench;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4-B9 end to end over HTTP: real JWT validation (local OIDC provider), the tenant's REPORTS_CLIENT service token,
 * a fake Semantic serving the A3 documents of PA-A and of «Empresas», the synthetic bench in a PostgreSQL with real
 * TLS read with {@code verify-full}, a tenancy registered and approved by two different persons through the API,
 * Reports' own database migrated by Flyway (V1 to V4), and the real Excel file downloaded and checked.
 * <p>
 * Nothing here is specific to a client: the documents are the contract examples and the synthetic bench of A0.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportRunApiIntegrationTest {

    private static final ContractFixtures.PaA PA_A = new ContractFixtures.PaA();
    private static final ReportFixtures.Empresas EMPRESAS = new ReportFixtures.Empresas(TlsBench.SCHEMA);
    private static final UUID TENANT = PA_A.tenantId;
    private static final UUID SOURCE = PA_A.dataSourceId;
    private static final UUID E_TENANT = EMPRESAS.tenantId;
    private static final UUID E_SOURCE = EMPRESAS.dataSourceId;
    /** A source of the same tenant whose CA certificate is not the server's: verify-full refuses to connect. */
    private static final UUID WRONG_CA_SOURCE = UUID.fromString("b9000000-0000-4000-8000-0000000000ca");
    /** A source configured without verify-full: it can never be registered. */
    private static final UUID NO_VERIFY_SOURCE = UUID.fromString("b9000000-0000-4000-8000-0000000000aa");

    private static final UUID RP = UUID.fromString("b9000000-0000-4000-8000-000000000001");
    private static final UUID RP_STALE = UUID.fromString("b9000000-0000-4000-8000-000000000002");
    private static final UUID RP_UNCONFIRMED = UUID.fromString("b9000000-0000-4000-8000-000000000003");
    private static final UUID RP_SEMANTIC_DOWN = UUID.fromString("b9000000-0000-4000-8000-000000000004");
    private static final UUID RP_EMPRESAS = UUID.fromString("b9000000-0000-4000-8000-000000000005");
    private static final UUID RP_WRONG_CA = UUID.fromString("b9000000-0000-4000-8000-000000000006");

    private static final String ANALYST = "analyst-b9";
    private static final String OTHER_ANALYST = "analyst-b9-other";
    private static final String STEWARD = "steward-b9";
    private static final String APPROVER = "approver-b9";
    private static final ReportRunDocuments DOCUMENTS = new ReportRunDocuments(
            RunContractSchemas.reportRun(ContractFixtures.READER),
            RunContractSchemas.reportArtifact(ContractFixtures.READER));

    private static boolean registered;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunHousekeeping housekeeping;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        TlsBench.ensure();
        Path storage = Files.createTempDirectory("nexora-b9-storage");
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
        account(registry, 0, TENANT, "reports-b9-a", "secret-b9-a");
        account(registry, 1, E_TENANT, "reports-b9-e", "secret-b9-e");
        registry.add("nexora.reports.enterprise.allowed-hosts[0]", TenantDatabase::host);
        registry.add("nexora.reports.enterprise.allowed-ports[0]", TenantDatabase::port);
        source(registry, 0, TENANT, SOURCE, "verify-full", TenantDatabase.rootCert());
        source(registry, 1, E_TENANT, E_SOURCE, "verify-full", TenantDatabase.rootCert());
        source(registry, 2, TENANT, WRONG_CA_SOURCE, "verify-full", TenantDatabase.wrongCa());
        source(registry, 3, TENANT, NO_VERIFY_SOURCE, "require", null);
        registry.add("nexora.reports.storage.directory", storage::toString);
    }

    private static void account(DynamicPropertyRegistry registry, int i, UUID tenant, String client, String secret) {
        String p = "nexora.reports.semantic.service-accounts[" + i + "].";
        registry.add(p + "tenant-id", tenant::toString);
        registry.add(p + "client-id", () -> client);
        registry.add(p + "client-secret", () -> secret);
    }

    private static void source(DynamicPropertyRegistry registry, int i, UUID tenant, UUID source, String sslMode,
                               String rootCert) {
        String p = "nexora.reports.enterprise.sources[" + i + "].";
        registry.add(p + "tenant-id", tenant::toString);
        registry.add(p + "data-source-id", source::toString);
        registry.add(p + "host", TenantDatabase::host);
        registry.add(p + "port", TenantDatabase::port);
        registry.add(p + "database", () -> TlsBench.DATABASE);
        registry.add(p + "username", () -> TlsBench.READER);
        registry.add(p + "password", () -> TenantDatabase.PASSWORD);
        registry.add(p + "ssl-mode", () -> sslMode);
        registry.add(p + "max-concurrent", () -> "4");
        if (rootCert != null) {
            registry.add(p + "ssl-root-cert", () -> rootCert);
        }
    }

    @BeforeAll
    static void platform() {
        TestPlatform.registerClient("reports-b9-a", "secret-b9-a", TENANT);
        TestPlatform.registerClient("reports-b9-e", "secret-b9-e", E_TENANT);
        dataSource(TENANT, SOURCE);
        dataSource(TENANT, WRONG_CA_SOURCE);
        dataSource(TENANT, NO_VERIFY_SOURCE);
        dataSource(E_TENANT, E_SOURCE);
        serve(TENANT, SOURCE, RP, PA_A.reportPlan, PA_A.spec, PA_A.plans, validity(RP, "CURRENT"));
        serve(TENANT, SOURCE, RP_STALE, PA_A.reportPlan, PA_A.spec, PA_A.plans, validity(RP_STALE, "STALE"));
        serve(TENANT, SOURCE, RP_UNCONFIRMED, PA_A.reportPlan, PA_A.spec, PA_A.plans, "{\"status\":\"MAYBE\"}");
        serve(E_TENANT, E_SOURCE, RP_EMPRESAS, EMPRESAS.reportPlan, EMPRESAS.spec, EMPRESAS.plans,
                validity(RP_EMPRESAS, "CURRENT"));
        ContractFixtures.PaA wrongCa = rebased(WRONG_CA_SOURCE);
        serve(TENANT, WRONG_CA_SOURCE, RP_WRONG_CA, wrongCa.reportPlan, wrongCa.spec, wrongCa.plans,
                validity(RP_WRONG_CA, "CURRENT"));
        TestPlatform.semantic(TENANT, plan(SOURCE, RP_SEMANTIC_DOWN), 503, "{\"code\":\"unavailable\"}");
    }

    // ------------------------------------------------------------------ Semantic as A3 serves it

    private static void dataSource(UUID tenant, UUID source) {
        TestPlatform.semantic(tenant, "/api/v1/data-sources/" + source, 200, """
                {"id":"%s","key":"banco","name":"Banco sintético","engine":"postgresql","databaseName":"bench",
                 "description":null,"status":"ACTIVE","createdAt":"2026-11-01T10:00:00Z","createdBy":"x",
                 "updatedAt":"2026-11-01T10:00:00Z","updatedBy":"x","version":0}""".formatted(source));
    }

    private static String plan(UUID source, UUID reportPlan) {
        return "/api/v1/data-sources/" + source + "/report-plans/" + reportPlan;
    }

    private static void serve(UUID tenant, UUID source, UUID reportPlan, Map<String, Object> rp,
                              Map<String, Object> spec, Map<UUID, Map<String, Object>> plans, String validity) {
        String base = plan(source, reportPlan);
        TestPlatform.semantic(tenant, base, 200, CanonicalJson.write(rp));
        TestPlatform.semantic(tenant, base + "/spec", 200, CanonicalJson.write(spec));
        plans.forEach((id, p) -> TestPlatform.semantic(tenant, base + "/logical-plans/" + id, 200,
                CanonicalJson.write(p)));
        TestPlatform.semantic(tenant, base + "/validity", 200, validity);
    }

    private static String validity(UUID reportPlan, String status) {
        String issues = "STALE".equals(status)
                ? "[{\"code\":\"OBJECT_CHANGED\",\"kind\":\"PHYSICAL_OBJECT\",\"referenceId\":null}]" : "[]";
        return "{\"reportPlanId\":\"" + reportPlan + "\",\"status\":\"" + status + "\",\"issues\":" + issues + "}";
    }

    /** PA-A for another data source of the same tenant, resealed as Semantic would compile it. */
    private static ContractFixtures.PaA rebased(UUID source) {
        ContractFixtures.PaA docs = new ContractFixtures.PaA();
        docs.reportPlan.put("dataSourceId", source.toString());
        docs.plans.values().forEach(p -> p.put("dataSourceId", source.toString()));
        docs.reseal();
        return docs;
    }

    // ------------------------------------------------------------------ tokens and calls

    private static String token(UUID tenant, String subject, String type, String... roles) {
        return TestPlatform.userTokenWithClaims(tenant, type, Map.of("sub", subject), roles);
    }

    private static String analyst(UUID tenant) {
        return token(tenant, ANALYST, "HUMAN", "REPORT_RUNNER");
    }

    private ResultActions runAs(String token, UUID source, UUID reportPlan) throws Exception {
        return mvc.perform(post("/api/v1/data-sources/" + source + "/report-plans/" + reportPlan + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions read(String token, UUID source, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/data-sources/" + source + "/report-runs/" + runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions download(String token, UUID source, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/data-sources/" + source + "/report-runs/" + runId + "/artifact")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static Map<String, Object> json(ResultActions actions) throws Exception {
        return ContractFixtures.READER.readObject(actions.andReturn().getResponse().getContentAsByteArray(),
                "response");
    }

    private static void problem(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value(code));
        String body = actions.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "at com.", TenantDatabase.PASSWORD, "secret-b9");
    }

    private ResultActions tenancy(String token, UUID source, String suffix, String body) throws Exception {
        var request = post("/api/v1/data-sources/" + source + "/tenancy/registrations" + suffix)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static final String BENCH_SCHEMAS = "{\"model\":\"DEDICATED_SCHEMAS\",\"schemas\":[\""
            + TlsBench.SCHEMA + "\",\"" + TlsBench.VOLUME_SCHEMA + "\"]}";

    /** Registers (steward) and approves (another person) the tenancy of a source; returns the registration id. */
    private UUID registerAndApprove(UUID tenant, UUID source) throws Exception {
        String steward = token(tenant, STEWARD, "HUMAN", "REPORTS_TENANCY_ADMIN");
        String approver = token(tenant, APPROVER, "HUMAN", "REPORTS_TENANCY_ADMIN");
        ResultActions registration = tenancy(steward, source, "", BENCH_SCHEMAS).andExpect(status().isCreated());
        Map<String, Object> body = json(registration);
        assertThat(body.get("status")).isEqualTo("PENDING");
        assertThat(body.get("schemas")).isEqualTo(List.of(TlsBench.SCHEMA, TlsBench.VOLUME_SCHEMA));
        UUID id = UUID.fromString((String) body.get("id"));
        tenancy(approver, source, "/" + id + "/approval", null).andExpect(status().isCreated());
        return id;
    }

    private synchronized void registeredPaA() throws Exception {
        if (!registered) {
            registerAndApprove(TENANT, SOURCE);
            registered = true;
        }
    }

    // ------------------------------------------------------------------ end to end

    @Test
    void paAIsRunReadAndDownloadedWithOneIntegrityChain() throws Exception {
        registeredPaA();

        ResultActions run = runAs(analyst(TENANT), SOURCE, RP).andExpect(status().isCreated())
                .andExpect(header().exists(ReportRunController.RUN_ID_HEADER))
                .andExpect(header().exists(HttpHeaders.LOCATION));
        Map<String, Object> doc = json(run);
        UUID runId = UUID.fromString((String) doc.get("runId"));

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("status")).isEqualTo("COMPLETED");
        assertThat(doc.get("reportPlanFingerprint")).isEqualTo(PA_A.reportPlan.get("fingerprint"));
        assertThat(run.andReturn().getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId);
        assertThat(json(read(analyst(TENANT), SOURCE, runId).andExpect(status().isOk()))).isEqualTo(doc);

        MvcResult file = download(analyst(TENANT), SOURCE, runId).andExpect(status().isOk()).andReturn();
        byte[] bytes = file.getResponse().getContentAsByteArray();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Map<String, Object> artifact = m(doc.get("artifact"));
        assertThat(sha).isEqualTo(artifact.get("sha256"));
        assertThat(file.getResponse().getHeader(ReportRunController.SHA256_HEADER)).isEqualTo(sha);
        assertThat((long) bytes.length).isEqualTo(artifact.get("sizeBytes"));
        assertThat(file.getResponse().getContentType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(file.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"nexora-report-" + runId + ".xlsx\"");
        assertThat(file.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(file.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(events(runId)).containsSubsequence("ARTIFACT_STORED", "ARTIFACT_RETRIEVED",
                "ARTIFACT_DELIVERED");
        assertThat(eventSha(runId, "ARTIFACT_DELIVERED")).isEqualTo(sha);

        Map<String, Object> expected = ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheetName(0)).isEqualTo("Reporte");
            assertThat(wb.getSheet("Listado de pozos").getLastRowNum()).isEqualTo(l(expected.get("detalle")).size());
        }
    }

    @Test
    void anotherReportRunsThroughTheSameEndpointAfterItsTenancyIsApprovedByAnotherPerson() throws Exception {
        String steward = token(E_TENANT, STEWARD, "HUMAN", "REPORTS_TENANCY_ADMIN");
        String approver = token(E_TENANT, APPROVER, "HUMAN", "REPORTS_TENANCY_ADMIN");

        // Without an approved tenancy nothing is read.
        Map<String, Object> refused = json(runAs(analyst(E_TENANT), E_SOURCE, RP_EMPRESAS)
                .andExpect(status().isCreated()));
        assertThat(m(refused.get("refusal")).get("code")).isEqualTo("tenant_scope_unverified");

        // Four eyes: the registrant cannot approve; the declaration must match what the role can use.
        problem(tenancy(steward, E_SOURCE, "", "{\"model\":\"DEDICATED_SCHEMAS\",\"schemas\":[\""
                + TlsBench.SCHEMA + "\"]}"), 422, "tenancy_capture_mismatch");
        problem(tenancy(steward, E_SOURCE, "", "{\"model\":\"SHARED_ROWS\",\"schemas\":[\"" + TlsBench.SCHEMA
                + "\"]}"), 422, "tenancy_model_not_supported");
        UUID id = UUID.fromString((String) json(tenancy(steward, E_SOURCE, "", BENCH_SCHEMAS)
                .andExpect(status().isCreated())).get("id"));
        problem(tenancy(steward, E_SOURCE, "/" + id + "/approval", null), 409, "tenancy_same_person");
        problem(tenancy(token(TENANT, APPROVER, "HUMAN", "REPORTS_TENANCY_ADMIN"), E_SOURCE,
                "/" + id + "/approval", null), 404, "not_found");
        tenancy(approver, E_SOURCE, "/" + id + "/approval", null).andExpect(status().isCreated());

        Map<String, Object> doc = json(runAs(analyst(E_TENANT), E_SOURCE, RP_EMPRESAS)
                .andExpect(status().isCreated()));
        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("status")).isEqualTo("COMPLETED");
        assertThat(l(doc.get("datasets"))).hasSize(3);

        byte[] listing = mvc.perform(get("/api/v1/data-sources/" + E_SOURCE + "/tenancy/registrations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
        List<Object> listed = l(ContractFixtures.READER.readObject(("{\"items\":"
                + new String(listing, java.nio.charset.StandardCharsets.UTF_8) + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "listing").get("items"));
        assertThat(m(listed.getFirst()).get("status")).isEqualTo("ACTIVE");
        assertThat(m(m(listed.getFirst()).get("database")).get("databaseName")).isEqualTo(TlsBench.DATABASE);

        // A revoked tenancy refuses the next run until a new version is registered and approved.
        tenancy(approver, E_SOURCE, "/" + id + "/revocation", null).andExpect(status().isCreated());
        Map<String, Object> afterRevocation = json(runAs(analyst(E_TENANT), E_SOURCE, RP_EMPRESAS)
                .andExpect(status().isCreated()));
        assertThat(m(afterRevocation.get("refusal")).get("code")).isEqualTo("tenant_scope_unverified");
        registerAndApprove(E_TENANT, E_SOURCE);
        assertThat(json(runAs(analyst(E_TENANT), E_SOURCE, RP_EMPRESAS).andExpect(status().isCreated()))
                .get("status")).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ security

    @Test
    void withoutAValidTokenForReportsNothingRuns() throws Exception {
        mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs"))
                .andExpect(status().isUnauthorized());
        runAs(TestPlatform.tokenWithAudience(TENANT, TestPlatform.SEMANTIC_AUDIENCE), SOURCE, RP)
                .andExpect(status().isUnauthorized());
        runAs(TestPlatform.untrustedToken(TENANT), SOURCE, RP).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutTheRoleOrAsAnAiPrincipalNothingRunsAndNoTenancyIsManaged() throws Exception {
        problem(runAs(token(TENANT, ANALYST, "HUMAN"), SOURCE, RP), 403, "forbidden");
        problem(runAs(token(TENANT, ANALYST, "AI", "REPORT_RUNNER"), SOURCE, RP), 403, "forbidden");
        problem(tenancy(token(TENANT, STEWARD, "HUMAN", "REPORT_RUNNER"), SOURCE, "", BENCH_SCHEMAS), 403,
                "forbidden");
        problem(tenancy(token(TENANT, STEWARD, "SERVICE", "REPORTS_TENANCY_ADMIN"), SOURCE, "", BENCH_SCHEMAS),
                403, "forbidden");
        problem(tenancy(token(TENANT, STEWARD, "AI", "REPORTS_TENANCY_ADMIN"), SOURCE, "", BENCH_SCHEMAS), 403,
                "forbidden");
    }

    @Test
    void aRunIsVisibleOnlyToItsOwnerInItsTenantAndSource() throws Exception {
        registeredPaA();
        UUID runId = UUID.fromString((String) json(runAs(analyst(TENANT), SOURCE, RP)
                .andExpect(status().isCreated())).get("runId"));

        String other = token(TENANT, OTHER_ANALYST, "HUMAN", "REPORT_RUNNER");
        problem(read(other, SOURCE, runId), 404, "not_found");
        problem(download(other, SOURCE, runId), 404, "not_found");
        problem(read(analyst(E_TENANT), SOURCE, runId), 404, "not_found");
        problem(read(analyst(TENANT), WRONG_CA_SOURCE, runId), 404, "not_found");
        problem(read(analyst(TENANT), SOURCE, UUID.randomUUID()), 404, "not_found");
    }

    // ------------------------------------------------------------------ refusals and failures

    @Test
    void aStaleOrUnconfirmedPlanIsARefusedRun() throws Exception {
        registeredPaA();

        Map<String, Object> stale = json(runAs(analyst(TENANT), SOURCE, RP_STALE).andExpect(status().isCreated()));
        Map<String, Object> unconfirmed = json(runAs(analyst(TENANT), SOURCE, RP_UNCONFIRMED)
                .andExpect(status().isCreated()));

        assertThat(DOCUMENTS.runVerdict(stale)).isEqualTo("OK");
        assertThat(m(stale.get("refusal")).get("code")).isEqualTo("plan_stale");
        assertThat(m(unconfirmed.get("refusal")).get("code")).isEqualTo("plan_validity_unconfirmed");
        assertThat(stale.get("artifact")).isNull();
    }

    @Test
    void anUnavailableSemanticOrAnUnknownPlanLeavesNoRun() throws Exception {
        problem(runAs(analyst(TENANT), SOURCE, RP_SEMANTIC_DOWN), 503, "semantic_unavailable");
        problem(runAs(analyst(TENANT), SOURCE, UUID.randomUUID()), 404, "not_found");
        problem(runAs(analyst(TENANT), UUID.randomUUID(), RP), 409, "data_source_not_configured");
    }

    @Test
    void verifyFullIsNeverWeakened() throws Exception {
        String steward = token(TENANT, STEWARD, "HUMAN", "REPORTS_TENANCY_ADMIN");
        problem(tenancy(steward, NO_VERIFY_SOURCE, "", BENCH_SCHEMAS), 422, "tls_required");

        Map<String, Object> doc = json(runAs(analyst(TENANT), WRONG_CA_SOURCE, RP_WRONG_CA)
                .andExpect(status().isCreated()));
        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(doc.get("artifact")).isNull();
        assertThat(l(doc.get("datasets"))).isEmpty();
    }

    @Test
    void anExpiredArtifactIsGoneWhileItsRunStaysReadable() throws Exception {
        UUID runId = insertExpiredRun(TENANT, SOURCE, ANALYST);

        problem(download(analyst(TENANT), SOURCE, runId), 410, "artifact_gone");
        Map<String, Object> doc = json(read(analyst(TENANT), SOURCE, runId).andExpect(status().isOk()));
        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
    }

    @Test
    void theScheduledReconciliationClosesAnAbandonedRunAsInterrupted() throws Exception {
        UUID runId = insertAbandonedRun(TENANT, SOURCE, ANALYST);

        // The schedule may have closed it already; either way it is closed once and only once.
        housekeeping.reconcile();

        problem(read(analyst(TENANT), SOURCE, runId), 409, "run_interrupted");
        assertThat(outcome(runId)).isEqualTo("INTERRUPTED");
        assertThat(housekeeping.reconcile()).isEqualTo(0);
    }

    // ------------------------------------------------------------------ Reports' database, as superuser

    private static List<String> events(UUID runId) throws SQLException {
        List<String> kinds = new ArrayList<>();
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT kind FROM reports.report_run_event WHERE run_id = '" + runId
                     + "' ORDER BY recorded_at, kind")) {
            while (rs.next()) {
                kinds.add(rs.getString(1));
            }
        }
        return kinds;
    }

    private static String eventSha(UUID runId, String kind) throws SQLException {
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT sha256 FROM reports.report_run_event WHERE run_id = '" + runId
                     + "' AND kind = '" + kind + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String outcome(UUID runId) throws SQLException {
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT kind FROM reports.report_run_outcome WHERE run_id = '" + runId
                     + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static UUID insertAbandonedRun(UUID tenant, UUID source, String owner) throws SQLException {
        UUID run = UUID.randomUUID();
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement st = c.createStatement()) {
            st.execute("INSERT INTO reports.report_run VALUES ('" + run + "', '" + tenant + "', '" + source + "', '"
                    + UUID.randomUUID() + "', '" + owner + "', 'HUMAN', now() - interval '10 minutes',"
                    + " now() - interval '8 minutes')");
        }
        return run;
    }

    private static UUID insertExpiredRun(UUID tenant, UUID source, String owner) throws SQLException {
        UUID run = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET LOCAL search_path = reports");
            st.execute("INSERT INTO report_run VALUES ('" + run + "', '" + tenant + "', '" + source + "', '"
                    + UUID.randomUUID() + "', '" + owner + "', 'HUMAN', now() - interval '10 days',"
                    + " now() - interval '10 days' + interval '5 minutes')");
            st.execute("INSERT INTO report_run_outcome (run_id, tenant_id, data_source_id, kind,"
                    + " report_plan_fingerprint, spec_fingerprint, snapshot, datasets, warnings) VALUES ('" + run
                    + "', '" + tenant + "', '" + source + "', 'COMPLETED', repeat('a', 64), repeat('b', 64), '1:1:',"
                    + " '[]', '[]')");
            st.execute("INSERT INTO report_artifact VALUES ('" + artifact + "', '" + run + "', '" + tenant + "', '"
                    + source + "', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',"
                    + " 'nexora-excel-report/1', encode(sha256('\\x0102'::bytea), 'hex'), 2,"
                    + " ARRAY['Reporte', 'Metodología'], now() - interval '9 days', now() - interval '2 days')");
            st.execute("INSERT INTO report_artifact_content VALUES ('" + artifact + "', '" + tenant + "', '\\x0102')");
            c.commit();
        }
        return run;
    }
}
