package com.nexora.reports.api;

import com.nexora.reports.execution.application.ReportRunDocuments;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.render.xlsx.Workbooks;
import com.nexora.reports.support.AuthenticPlans;
import com.nexora.reports.support.AuthenticWorkbook;
import com.nexora.reports.support.BenchReference;
import com.nexora.reports.support.RunApiSetup;
import com.nexora.reports.support.RunProbes;
import com.nexora.reports.support.TestPlatform;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.support.RunApiSetup.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4-B10-c.1: Reports executes, through its public API and its generic engine, the authentic report plans that the
 * real Nexora Semantic compiled in B10-a ({@link AuthenticPlans}: byte for byte, verified, never resealed). Everything
 * of Reports is real: JWT validation, the tenant's REPORTS_CLIENT identity, the independent validation (B2),
 * translation (B3), one snapshot of the synthetic bench behind TLS {@code verify-full} with a tenancy approved by two
 * persons (B4, B5), assembly (B6), Excel (B7) and the run registry (B8). Only the <b>transport</b> of Semantic is
 * simulated ({@link TestPlatform} serves the exported bytes): this is evidence of level B, not a live integration
 * between the two services.
 * <p>
 * The seven CURRENT cases are data ({@code referencia-b10c/casos.json}): each downloaded Excel file is compared cell by
 * cell with SQL written by hand from the authentic specification and run apart on the bench with its read-only role
 * ({@link BenchReference}). The files, the run documents and a summary are kept in {@code target/b10c1-evidence} for
 * the mini demo. Scenarios E3 to E9 and E11 to E14 of the B10-c.1 proposal; E10 (no physical connection for a STALE
 * plan) is {@link AuthenticPlanStaleIntegrationTest}; E1 and E2 are unit tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RunProbes.class)
class AuthenticPlanRunIntegrationTest {

    private static final AuthenticPlans PLANS = AuthenticPlans.load();
    private static final UUID TENANT = PLANS.tenantId();
    private static final UUID SOURCE = PLANS.dataSourceId();
    /** Another tenant with its own source, for the isolation scenarios (E13). */
    private static final UUID OTHER_TENANT = UUID.fromString("b10c0000-0000-4000-8000-000000000001");
    private static final UUID OTHER_SOURCE = UUID.fromString("b10c0000-0000-4000-8000-000000000002");
    private static final String ANALYST = "analyst-b10c1";
    private static final Path EVIDENCE = Path.of("target", "b10c1-evidence");
    private static final Map<String, Object> SUMMARY = new TreeMap<>();
    private static final ReportRunDocuments DOCUMENTS = new ReportRunDocuments(
            RunContractSchemas.reportRun(ContractFixtures.READER),
            RunContractSchemas.reportArtifact(ContractFixtures.READER));

    private static boolean registered;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        RunApiSetup.common(registry, Files.createTempDirectory("nexora-b10c1"));
        RunApiSetup.account(registry, 0, TENANT, "reports-b10c1", "secret-b10c1");
        RunApiSetup.account(registry, 1, OTHER_TENANT, "reports-b10c1-other", "secret-b10c1-other");
        RunApiSetup.source(registry, 0, TENANT, SOURCE);
        RunApiSetup.source(registry, 1, OTHER_TENANT, OTHER_SOURCE);
    }

    @BeforeEach
    void platform() {
        RunProbes.reset();
        PLANS.serve(TENANT, SOURCE);
        for (AuthenticPlans.Case c : PLANS.cases().values()) {
            PLANS.validities(TENANT, SOURCE, c.id(), c.validityFile());
        }
        // A Semantic that answers another tenant with these documents (E13): Reports must refuse them.
        PLANS.serve(OTHER_TENANT, OTHER_SOURCE);
        PLANS.validities(OTHER_TENANT, OTHER_SOURCE, "pa-a", PLANS.of("pa-a").validityFile());
    }

    static Stream<String> currentCases() {
        return l(BenchReference.cases().get("casos")).stream().map(c -> (String) m(c).get("caso"));
    }

    // ------------------------------------------------------------------ E3 to E9, E14: the seven CURRENT cases

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentCases")
    void eachCurrentCaseRunsOnTheGenericEngineAndMatchesItsReferenceCellByCell(String caseId) throws Exception {
        registered();
        Map<String, Object> expected = expectation(caseId);
        AuthenticPlans.Case c = PLANS.of(caseId);
        RunProbes.resetCounts();

        ResultActions run = run(analyst(TENANT), SOURCE, c.reportPlanId()).andExpect(status().isCreated());
        byte[] runBytes = run.andReturn().getResponse().getContentAsByteArray();
        Map<String, Object> doc = ContractFixtures.READER.readObject(runBytes, "report-run");

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("status")).isEqualTo(expected.get("estado"));
        assertThat(doc.get("refusal")).isNull();
        assertThat(doc.get("reportPlanFingerprint")).isEqualTo(c.reportPlanFingerprint());
        List<Object> datasets = l(doc.get("datasets"));
        List<Object> expectedDatasets = l(expected.get("datasets"));
        assertThat(datasets).hasSize(expectedDatasets.size());
        Map<String, List<List<Object>>> reference = new LinkedHashMap<>();
        for (int i = 0; i < expectedDatasets.size(); i++) {
            Map<String, Object> e = m(expectedDatasets.get(i));
            Map<String, Object> d = m(datasets.get(i));
            String datasetId = (String) e.get("datasetId");
            assertThat(d.get("datasetId")).isEqualTo(datasetId);
            assertThat(d.get("planId")).as("the authentic plan of " + datasetId)
                    .isEqualTo(c.planIds().get(datasetId).toString());
            assertThat(((Number) d.get("rowCount")).longValue()).as(datasetId)
                    .isEqualTo(((Number) e.get("filas")).longValue());
            assertThat(d.get("truncated")).as(datasetId).isEqualTo(e.get("truncado"));
            List<List<Object>> rows = BenchReference.rows((String) e.get("referencia"));
            assertThat((long) rows.size()).as("reference rows of " + datasetId)
                    .isEqualTo(((Number) e.get("filas")).longValue());
            reference.put(datasetId, rows);
        }
        // One generic path for every case: one destination, one resolution of credentials, one read.
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isEqualTo(1);
        assertThat(RunProbes.readsStarted()).isEqualTo(1);

        UUID runId = UUID.fromString((String) doc.get("runId"));
        MvcResult file = download(analyst(TENANT), runId).andExpect(status().isOk()).andReturn();
        byte[] xlsx = file.getResponse().getContentAsByteArray();
        Map<String, Object> artifact = m(doc.get("artifact"));
        assertThat(AuthenticPlans.sha256(xlsx)).isEqualTo(artifact.get("sha256"))
                .isEqualTo(file.getResponse().getHeader(ReportRunController.SHA256_HEADER));
        assertThat(DOCUMENTS.artifactVerdict(artifact)).isEqualTo("OK");

        AuthenticWorkbook.assertCells(PLANS.spec(caseId), xlsx, reference);
        evidence(caseId, doc, runBytes, xlsx);
    }

    /** E3: PA-A also equals the independent expected file of the bench (A0), not only the reference SQL. */
    @Test
    void paAAlsoEqualsTheExpectedFileOfTheBench() throws Exception {
        Map<String, Object> bench = ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
        List<List<Object>> resumen = BenchReference.rows("pa-a.resumen.sql");
        List<List<Object>> detalle = BenchReference.rows("pa-a.detalle.sql");
        assertThat(resumen).hasSize(l(bench.get("resumen")).size());
        for (int i = 0; i < resumen.size(); i++) {
            Map<String, Object> row = m(l(bench.get("resumen")).get(i));
            assertThat(resumen.get(i)).isEqualTo(java.util.Arrays.asList(row.get("pais"), row.get("campo"),
                    ((Number) row.get("pozos")).doubleValue()));
        }
        assertThat(BenchReference.rows("pa-a.total.sql").getFirst().getFirst())
                .isEqualTo(((Number) bench.get("total")).doubleValue());
        assertThat(detalle.stream().map(List::getFirst).toList()).isEqualTo(l(bench.get("detalle")).stream()
                .map(r -> m(r).get("nombre")).toList());
        Map<String, Object> volume = m(bench.get("volumen"));
        assertThat(BenchReference.rows("volumen.total.sql").getFirst().getFirst())
                .isEqualTo(((Number) volume.get("total")).doubleValue());
        assertThat(BenchReference.rows("volumen.detalle.sql").subList(0, 3).stream().map(List::getFirst).toList())
                .isEqualTo(l(volume.get("primeros_detalle")));
    }

    /** E4 (R4-13): the governed dimension and metric of the catalog, with their revisions, reach the methodology. */
    @Test
    void theGovernedDefinitionsOfTheCatalogReachTheMethodology() throws Exception {
        List<List<Object>> methodology = sheet(runAndDownload("pa-a-gobernado"), "Metodología");
        assertThat(rowWith(methodology, "DIMENSION pais")).contains((double) PLANS.definitionVersion("pais"));
        assertThat(rowWith(methodology, "METRIC pozos")).contains((double) PLANS.definitionVersion("pozos"));
    }

    /**
     * E6 to E8 (R4-14): each filter is stated in the header (EX-02) as the specification asked it, with its accent;
     * the read itself uses the canonical literal of the plan (the number 300, whose rows the cell comparison checks).
     * Observation B10c1-O1: for NUMBER the header shows the literal of the question ({@code 300.000}), not the
     * canonical value; EX-02 does not say which one, so this records the behaviour without judging it.
     */
    @Test
    void theFiltersAreStatedInTheHeaderAsAskedAndReadWithTheCanonicalLiteral() throws Exception {
        assertThat(filters("filtro-in")).contains("Campo es uno de Neuquén, Llanos");
        assertThat(filters("filtro-numero")).contains("Identificador es mayor o igual que 300.000");
        assertThat(filters("filtro-nulo")).contains("Campo está vacío");
        assertThat(filters("empresas")).contains("is_active es igual a Sí")
                .contains("Código es uno de GPA, SIC-FLU, GEA, GPA-SER").contains("Nombre no está vacío");
        Map<String, Object> filter = PLANS.plans("filtro-numero").values().stream()
                .flatMap(p -> l(p.get("operations")).stream()).map(ContractFixtures::m)
                .filter(o -> "FILTER".equals(o.get("op"))).findFirst().orElseThrow();
        assertThat(l(m(l(filter.get("predicates")).getFirst()).get("values")))
                .containsExactly(Map.of("type", "NUMBER", "value", "300"));
    }

    /** E9: the volume case is PARTIAL with its warning; its total comes from its own dataset (12 000). */
    @Test
    void theVolumeCaseIsPartialWithAWarningAndItsOwnTotal() throws Exception {
        registered();
        Map<String, Object> doc = json(run(analyst(TENANT), SOURCE, PLANS.of("volumen").reportPlanId())
                .andExpect(status().isCreated()));
        assertThat(doc.get("status")).isEqualTo("PARTIAL");
        Map<String, Object> warning = m(l(doc.get("warnings")).getFirst());
        assertThat(warning.get("code")).isEqualTo("dataset_truncated");
        assertThat(warning.get("datasetId")).isEqualTo("detalle");
    }

    // ------------------------------------------------------------------ E10 (functional) and E11: validity

    /** E10 (functional part): STALE before reading; the physical part is AuthenticPlanStaleIntegrationTest. */
    @Test
    void theStaleCaseIsRefusedBeforeReadingWithoutResolvingCredentials() throws Exception {
        registered();
        AuthenticPlans.Case stale = PLANS.of("caducado");
        PLANS.validities(TENANT, SOURCE, "caducado", stale.validityFile());
        RunProbes.resetCounts();

        Map<String, Object> doc = json(run(analyst(TENANT), SOURCE, stale.reportPlanId())
                .andExpect(status().isCreated()));

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("plan_stale");
        assertThat(doc.get("artifact")).isNull();
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
    }

    /** E11: Semantic's own answers, CURRENT before reading and STALE after: the rows are discarded. */
    @Test
    void theStaleCaseAfterReadingDiscardsItsRows() throws Exception {
        registered();
        AuthenticPlans.Case stale = PLANS.of("caducado");
        PLANS.validities(TENANT, SOURCE, "caducado", "respuestas/caducado.validity-antes.json",
                stale.validityFile());
        RunProbes.resetCounts();

        Map<String, Object> doc = json(run(analyst(TENANT), SOURCE, stale.reportPlanId())
                .andExpect(status().isCreated()));
        UUID runId = UUID.fromString((String) doc.get("runId"));

        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("plan_changed_during_execution");
        assertThat(l(doc.get("datasets"))).isEmpty();
        assertThat(doc.get("artifact")).isNull();
        assertThat(RunProbes.credentialsResolved()).isEqualTo(1);
        assertThat(RunProbes.readsStarted()).isEqualTo(1);
        assertThat(RunApiSetup.events(runId)).doesNotContain("ARTIFACT_STORED");
    }

    // ------------------------------------------------------------------ E12, E13: access and isolation

    @Test
    void withoutAValidPrincipalOrRoleNothingIsReached() throws Exception {
        UUID rp = PLANS.of("pa-a").reportPlanId();
        int runs = RunApiSetup.runs(TENANT);
        RunProbes.resetCounts();

        run(null, SOURCE, rp).andExpect(status().isUnauthorized());
        run(TestPlatform.untrustedToken(TENANT), SOURCE, rp).andExpect(status().isUnauthorized());
        problem(run(token(TENANT, ANALYST, "HUMAN"), SOURCE, rp), 403, "forbidden");
        problem(run(token(TENANT, "intelligence", "AI", "REPORT_RUNNER"), SOURCE, rp), 403, "forbidden");

        assertThat(TestPlatform.calls()).isEmpty();
        assertThat(RunProbes.availabilityQueries()).isZero();
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(RunApiSetup.runs(TENANT)).isEqualTo(runs);
    }

    /**
     * B10-c.1-FIX1 (A1): Semantic answers another tenant's source ({@code OTHER_SOURCE}) with the authentic
     * description of the export's source ({@code respuestas/data-source.json}, served as it is by
     * {@link #platform()}): Reports refuses the incoherent answer at step 3, before the run starts (API.md: 502
     * {@code semantic_contract_violation}). Nothing after the data source description is asked of Semantic.
     */
    @Test
    void aSemanticDescribingAnotherSourceIsAContractViolationAndNothingStarts() throws Exception {
        UUID rp = PLANS.of("pa-a").reportPlanId();
        int otherRuns = RunApiSetup.runs(OTHER_TENANT);
        RunProbes.resetCounts();

        problem(run(analyst(OTHER_TENANT), OTHER_SOURCE, rp), 502, "semantic_contract_violation");

        assertThat(RunApiSetup.runs(OTHER_TENANT)).as("runs created").isEqualTo(otherRuns);
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(TestPlatform.calls()).as("calls to Semantic")
                .containsExactly(new TestPlatform.Call(OTHER_TENANT.toString(), "/api/v1/data-sources/" + OTHER_SOURCE));
    }

    /**
     * E13, B10-c.1-FIX1 (A2): Semantic describes another tenant's own source coherently (a synthetic description of
     * {@code OTHER_SOURCE}, built here; it is not, and replaces none of, the exported documents) and then serves that
     * tenant the authentic documents of {@code pa-a}, byte for byte. Reports' validation refuses them as an identity
     * mismatch; nothing is resolved or read, and runs stay invisible across tenants and owners.
     */
    @Test
    void anotherTenantNeitherReachesTheSourceNorUsesTheseDocuments() throws Exception {
        registered();
        UUID rp = PLANS.of("pa-a").reportPlanId();
        TestPlatform.semantic(OTHER_TENANT, "/api/v1/data-sources/" + OTHER_SOURCE, 200,
                syntheticDataSource(OTHER_SOURCE));
        PLANS.serveDocuments(OTHER_TENANT, OTHER_SOURCE);
        RunProbes.resetCounts();

        // The source of the export is not configured for another tenant: answered before Semantic.
        problem(run(analyst(OTHER_TENANT), SOURCE, rp), 409, "data_source_not_configured");
        assertThat(TestPlatform.calls()).isEmpty();

        // A Semantic answering another tenant with these documents: refused by the validation, nothing read.
        int otherRuns = RunApiSetup.runs(OTHER_TENANT);
        Map<String, Object> doc = json(run(analyst(OTHER_TENANT), OTHER_SOURCE, rp).andExpect(status().isCreated()));
        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("identity_mismatch");
        assertThat(doc.get("artifact")).isNull();
        assertThat(RunApiSetup.runs(OTHER_TENANT)).isEqualTo(otherRuns + 1);
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(TestPlatform.calls()).extracting(TestPlatform.Call::tenant).containsOnly(OTHER_TENANT.toString());
        assertThat(TestPlatform.calls()).extracting(TestPlatform.Call::path)
                .contains(AuthenticPlans.path(OTHER_SOURCE, rp)).noneMatch(p -> p.endsWith("/validity"));

        // That refused run is invisible to the export's tenant.
        UUID refusedId = UUID.fromString((String) doc.get("runId"));
        problem(mvc.perform(get("/api/v1/data-sources/" + OTHER_SOURCE + "/report-runs/" + refusedId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst(TENANT))), 404, "not_found");

        // A run of the export's tenant is invisible to another owner and to another tenant.
        Map<String, Object> mine = json(run(analyst(TENANT), SOURCE, rp).andExpect(status().isCreated()));
        UUID runId = UUID.fromString((String) mine.get("runId"));
        for (String other : List.of(token(TENANT, "analyst-other", "HUMAN", "REPORT_RUNNER"), analyst(OTHER_TENANT))) {
            problem(mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)), 404, "not_found");
            problem(download(other, runId), 404, "not_found");
        }
    }

    /** Test data, not an exported document: how a coherent Semantic would describe another tenant's own source. */
    private static String syntheticDataSource(UUID dataSourceId) {
        return "{\"id\":\"" + dataSourceId + "\",\"key\":\"b10c1-fuente-sintetica\","
                + "\"name\":\"Fuente sintética de otro tenant (no auténtica)\",\"engine\":\"postgresql\","
                + "\"databaseName\":\"otra_fuente_sintetica\",\"status\":\"ACTIVE\"}";
    }

    // ------------------------------------------------------------------ E14: no code per report

    /** E14 (R4-24, R4-26): no id, table, dataset or case of the export is named by the production code. */
    @Test
    void noProductionCodeNamesACaseATableOrAnIdOfTheExport() throws IOException {
        List<String> names = new ArrayList<>(List.of("pa-a-gobernado", "filtro-in", "filtro-numero", "filtro-nulo",
                "caducado", "empresa_operadora", "por_operadora", "total_empresas", "detalle_empresas",
                "wm_well", "etp_company", "bkw_operators", "wellnode", TENANT.toString(), SOURCE.toString()));
        for (AuthenticPlans.Case c : PLANS.cases().values()) {
            names.add(c.reportPlanId().toString());
            c.planIds().values().forEach(id -> names.add(id.toString()));
        }
        List<String> hits = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    for (String name : names) {
                        if (text.contains(name)) {
                            hits.add(f + ": " + name);
                        }
                    }
                }
            }
        }
        assertThat(hits).isEmpty();
    }

    @AfterAll
    static void writeSummary() throws IOException {
        if (SUMMARY.isEmpty()) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evidence", "A4-B10-c.1: Reports ejecutando los planes auténticos de B10-a (nivel B: "
                + "transporte de Semantic simulado; no es una integración viva)");
        summary.put("generatedBy", AuthenticPlanRunIntegrationTest.class.getName());
        summary.put("export", Map.of("manifestSha256", AuthenticPlans.sha256(AuthenticPlans.bytes("manifest.json")),
                "tenantId", TENANT.toString(), "dataSourceId", SOURCE.toString()));
        summary.put("cases", SUMMARY);
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("resumen.json"), CanonicalJson.write(summary), StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(EVIDENCE)) {
            String sums = files.filter(p -> !p.getFileName().toString().equals("SHA256SUMS")).sorted()
                    .map(p -> {
                        try {
                            return AuthenticPlans.sha256(Files.readAllBytes(p)) + "  " + p.getFileName();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).collect(Collectors.joining("\n", "", "\n"));
            Files.writeString(EVIDENCE.resolve("SHA256SUMS"), sums, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> expectation(String caseId) {
        return l(BenchReference.cases().get("casos")).stream().map(ContractFixtures::m)
                .filter(c -> caseId.equals(c.get("caso"))).findFirst().orElseThrow();
    }

    private static synchronized void evidence(String caseId, Map<String, Object> doc, byte[] runBytes, byte[] xlsx)
            throws IOException {
        Files.createDirectories(EVIDENCE);
        Files.write(EVIDENCE.resolve(caseId + ".xlsx"), xlsx);
        Files.write(EVIDENCE.resolve(caseId + ".report-run.json"), runBytes);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", doc.get("status"));
        entry.put("reportPlanId", PLANS.of(caseId).reportPlanId().toString());
        entry.put("reportPlanFingerprint", doc.get("reportPlanFingerprint"));
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Object d : l(doc.get("datasets"))) {
            rows.put((String) m(d).get("datasetId"), m(d).get("rowCount"));
        }
        entry.put("rowCount", rows);
        entry.put("xlsxSha256", AuthenticPlans.sha256(xlsx));
        entry.put("comparedWith", "referencia-b10c (SQL independiente, rol de solo lectura del banco)");
        SUMMARY.put(caseId, entry);
    }

    private static String analyst(UUID tenant) {
        return token(tenant, ANALYST, "HUMAN", "REPORT_RUNNER");
    }

    private synchronized void registered() throws Exception {
        if (registered) {
            return;
        }
        String steward = token(TENANT, "steward-b10c1", "HUMAN", "REPORTS_TENANCY_ADMIN");
        String approver = token(TENANT, "approver-b10c1", "HUMAN", "REPORTS_TENANCY_ADMIN");
        byte[] created = mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + steward)
                        .contentType(MediaType.APPLICATION_JSON).content(RunApiSetup.BENCH_SCHEMAS))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        String id = (String) ContractFixtures.READER.readObject(created, "registration").get("id");
        mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations/" + id + "/approval")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver)).andExpect(status().isCreated());
        registered = true;
    }

    private ResultActions run(String token, UUID source, UUID reportPlan) throws Exception {
        var request = post("/api/v1/data-sources/" + source + "/report-plans/" + reportPlan + "/runs");
        return mvc.perform(token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions download(String token, UUID runId) throws Exception {
        return mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId + "/artifact")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private byte[] runAndDownload(String caseId) throws Exception {
        registered();
        Map<String, Object> doc = json(run(analyst(TENANT), SOURCE, PLANS.of(caseId).reportPlanId())
                .andExpect(status().isCreated()));
        assertThat(doc.get("status")).as(caseId).isEqualTo("COMPLETED");
        return download(analyst(TENANT), UUID.fromString((String) doc.get("runId"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    /** Every text of the sheet «Reporte», from the row «Filtros» on (the header states each filter there). */
    private String filters(String caseId) throws Exception {
        List<List<Object>> report = sheet(runAndDownload(caseId), "Reporte");
        int from = report.indexOf(AuthenticWorkbook.rowStartingWith(report, "Filtros"));
        return report.subList(from, report.size()).stream().flatMap(List::stream).filter(v -> v != null)
                .map(String::valueOf).collect(Collectors.joining(" | "));
    }

    private static List<List<Object>> sheet(byte[] xlsx, String name) throws IOException {
        try (XSSFWorkbook wb = Workbooks.open(xlsx)) {
            return Workbooks.rows(wb.getSheet(name));
        }
    }

    private static List<Object> rowWith(List<List<Object>> rows, Object cell) {
        return rows.stream().filter(r -> r.contains(cell)).findFirst()
                .orElseThrow(() -> new AssertionError("no row with " + cell));
    }

    private static Map<String, Object> json(ResultActions actions) throws Exception {
        return ContractFixtures.READER.readObject(actions.andReturn().getResponse().getContentAsByteArray(),
                "response");
    }

    private static void problem(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value(code));
    }
}
