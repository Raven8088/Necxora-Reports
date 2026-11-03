package com.nexora.reports.api;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.domain.ReportsCapabilities;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.support.BenchSessions;
import com.nexora.reports.support.RunApiSetup;
import com.nexora.reports.support.RunProbes;
import com.nexora.reports.support.TenantDatabase;
import com.nexora.reports.support.TestPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * A4-B10-b over HTTP, on the same platform as A4-B9 (real JWT validation, a fake Semantic serving report plans as A3
 * does, the synthetic bench behind real TLS {@code verify-full}, a tenancy registered and approved by two persons,
 * Reports' own database). The documents are the PA-A contract examples rebased to this class's tenant: they are
 * <b>not</b> plans compiled by Semantic for this test (that is B10-a/B10-c), and nothing here is a live integration
 * between services.
 * <ul>
 *   <li>T-9 (R4-8, additional): validity before and after reading, and what each case reaches: destinations looked
 *       up without their secret, credentials resolved, reads started and, for B10-O3 (D-O3-4), physical sessions
 *       to the bench counted by PostgreSQL itself;</li>
 *   <li>T-11 (additional): a run takes no body, over MockMvc and over a real socket with a chunked body;</li>
 *   <li>T-5, part independent of Semantic's exports (R4-23): what 401, 403 and the isolation reach, and that no
 *       question, filter value, row value, secret, token, external message or exception message reaches the logs,
 *       the error responses or what Reports stores;</li>
 *   <li>T-10 over HTTP (D-A4-8, additional): who reads the capabilities and what they are;</li>
 *   <li>D-B10-4 over HTTP: what is not announced (more joins in a plan, more sections in a specification) is a
 *       refused run.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(RunProbes.class)
@ExtendWith(OutputCaptureExtension.class)
class ReportRunSafeguardsIntegrationTest {

    private static final UUID TENANT = UUID.fromString("b10b0000-0000-4000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("b10b0000-0000-4000-8000-000000000002");
    private static final UUID OTHER_TENANT = UUID.fromString("b10b0000-0000-4000-8000-000000000003");
    /** B10-O3: configured sources of the same tenant that no other test uses: no pool, no idle connection. */
    private static final UUID STALE_SOURCE = UUID.fromString("b10b0000-0000-4000-8000-000000000004");
    private static final UUID CONTROL_SOURCE = UUID.fromString("b10b0000-0000-4000-8000-000000000005");
    private static final String CLIENT = "reports-b10-safeguards";
    private static final String SECRET = "secreto-CENTINELA-B10-cliente";

    private static final UUID RP = rp(1);
    private static final UUID RP_SENTINELS = rp(2);
    private static final UUID RP_PARTIAL = rp(3);
    private static final UUID RP_STALE_BEFORE = rp(4);
    private static final UUID RP_STALE_AFTER = rp(5);
    private static final UUID RP_SILENT_AFTER = rp(6);
    private static final UUID RP_SEMANTIC_DOWN = rp(7);
    private static final UUID RP_GARBAGE = rp(8);
    private static final UUID RP_FAIL_BEFORE = rp(9);
    private static final UUID RP_FAIL_AFTER = rp(10);
    private static final UUID RP_TWO_SECTIONS = rp(11);
    private static final UUID RP_THREE_JOINS = rp(12);
    private static final UUID RP_PHYSICAL_STALE = rp(13);
    private static final UUID RP_PHYSICAL_CONTROL = rp(14);

    private static final String QUESTION = "CENTINELA-PREGUNTA-B10 ¿cuántos pozos hay?";
    private static final String FILTER = "CENTINELA-FILTRO-B10-Ñandú";
    private static final String SEMANTIC_BODY = "CENTINELA-SEMANTIC-B10";
    private static final String EXCEPTION = "CENTINELA-EXCEPCION-B10";
    /** A JWT anywhere in a text (header and payload are base64url JSON objects). */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}");
    private static final String STATUS_COLUMN = "7a3f0000-0000-4000-8000-000000000016";

    private static final String ANALYST = "analyst-b10";
    private static final List<String> TOKENS = new ArrayList<>();
    private static boolean registered;
    private static Path storage;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ReportsCapabilities capabilities;

    @Value("${local.server.port}")
    private int port;

    private static UUID rp(int n) {
        return UUID.fromString("b10b0000-0000-4000-8000-0000000001" + String.format("%02d", n));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storage = Files.createTempDirectory("nexora-b10-safeguards");
        RunApiSetup.common(registry, storage);
        RunApiSetup.account(registry, 0, TENANT, CLIENT, SECRET);
        RunApiSetup.source(registry, 0, TENANT, SOURCE);
        RunApiSetup.source(registry, 1, TENANT, STALE_SOURCE);
        RunApiSetup.source(registry, 2, TENANT, CONTROL_SOURCE);
        registry.add("logging.level.com.nexora", () -> "DEBUG");
    }

    @BeforeEach
    void platform() {
        RunProbes.reset();
        RunApiSetup.dataSource(TENANT, SOURCE);
        ContractFixtures.PaA paA = RunApiSetup.paA(TENANT, SOURCE);
        serveCurrent(RP, paA);
        serveCurrent(RP_SENTINELS, withSentinels());
        ContractFixtures.PaA partial = RunApiSetup.paA(TENANT, SOURCE);
        RunApiSetup.limit(partial, "detalle", 10);
        partial.reseal();
        serveCurrent(RP_PARTIAL, partial);
        RunApiSetup.serve(TENANT, SOURCE, RP_STALE_BEFORE, paA);
        RunApiSetup.validities(TENANT, SOURCE, RP_STALE_BEFORE, RunApiSetup.validity(RP_STALE_BEFORE, "STALE"));
        RunApiSetup.serve(TENANT, SOURCE, RP_STALE_AFTER, paA);
        RunApiSetup.validities(TENANT, SOURCE, RP_STALE_AFTER, RunApiSetup.validity(RP_STALE_AFTER, "CURRENT"),
                RunApiSetup.validity(RP_STALE_AFTER, "STALE"));
        RunApiSetup.serve(TENANT, SOURCE, RP_SILENT_AFTER, paA);
        RunApiSetup.validities(TENANT, SOURCE, RP_SILENT_AFTER, RunApiSetup.validity(RP_SILENT_AFTER, "CURRENT"),
                new TestPlatform.Response(503, "{\"code\":\"unavailable\",\"detail\":\"" + SEMANTIC_BODY + "\"}"));
        TestPlatform.semantic(TENANT, RunApiSetup.plan(SOURCE, RP_SEMANTIC_DOWN), 503,
                "{\"code\":\"unavailable\",\"detail\":\"" + SEMANTIC_BODY + "\"}");
        TestPlatform.semantic(TENANT, RunApiSetup.plan(SOURCE, RP_GARBAGE), 200, "<html>" + SEMANTIC_BODY);
        serveCurrent(RP_FAIL_BEFORE, paA);
        RunProbes.failSemanticCall("reportPlan", RP_FAIL_BEFORE, 1, EXCEPTION + " " + FILTER);
        serveCurrent(RP_FAIL_AFTER, paA);
        RunProbes.failSemanticCall("validity", RP_FAIL_AFTER, 2, EXCEPTION + " " + RunApiSetup.wellNames().get(0));
        ContractFixtures.PaA twoSections = RunApiSetup.paA(TENANT, SOURCE);
        l(twoSections.spec.get("sections")).add(Map.of("id", "otra", "title", "Otra sección", "items",
                List.of(Map.of("type", "table", "dataset", "detalle"))));
        twoSections.reseal();
        serveCurrent(RP_TWO_SECTIONS, twoSections);
        ContractFixtures.PaA threeJoins = RunApiSetup.paA(TENANT, SOURCE);
        RunApiSetup.joins(threeJoins, 3);
        threeJoins.reseal();
        serveCurrent(RP_THREE_JOINS, threeJoins);
        // B10-O3: PA-A on the two unused sources, STALE on one and CURRENT on the other (no tenancy registered).
        RunApiSetup.dataSource(TENANT, STALE_SOURCE);
        RunApiSetup.serve(TENANT, STALE_SOURCE, RP_PHYSICAL_STALE, RunApiSetup.paA(TENANT, STALE_SOURCE));
        RunApiSetup.validities(TENANT, STALE_SOURCE, RP_PHYSICAL_STALE,
                RunApiSetup.validity(RP_PHYSICAL_STALE, "STALE"));
        RunApiSetup.dataSource(TENANT, CONTROL_SOURCE);
        RunApiSetup.serve(TENANT, CONTROL_SOURCE, RP_PHYSICAL_CONTROL, RunApiSetup.paA(TENANT, CONTROL_SOURCE));
        RunApiSetup.validities(TENANT, CONTROL_SOURCE, RP_PHYSICAL_CONTROL,
                RunApiSetup.validity(RP_PHYSICAL_CONTROL, "CURRENT"));
    }

    private static void serveCurrent(UUID reportPlan, ContractFixtures.PaA docs) {
        RunApiSetup.serve(TENANT, SOURCE, reportPlan, docs);
        RunApiSetup.validities(TENANT, SOURCE, reportPlan, RunApiSetup.validity(reportPlan, "CURRENT"));
    }

    /** PA-A asked with a sentinel question and filtered by a sentinel value (in the specification and the plan). */
    private static ContractFixtures.PaA withSentinels() {
        ContractFixtures.PaA docs = RunApiSetup.paA(TENANT, SOURCE);
        m(docs.spec.get("request")).put("text", QUESTION);
        Map<String, Object> value = new java.util.LinkedHashMap<>(Map.of("type", "TEXT", "value", FILTER));
        Map<String, Object> specFilter = new java.util.LinkedHashMap<>();
        specFilter.put("column", new java.util.LinkedHashMap<>(Map.of("alias", "w", "object", STATUS_COLUMN)));
        specFilter.put("operator", "IN");
        specFilter.put("values", new ArrayList<>(List.of(value)));
        specFilter.put("literalSource", "QUESTION");
        l(m(l(docs.spec.get("datasets")).get(2)).get("filters")).add(specFilter);
        Map<String, Object> predicate = new java.util.LinkedHashMap<>();
        predicate.put("column", new java.util.LinkedHashMap<>(Map.of("alias", "w", "column", STATUS_COLUMN)));
        predicate.put("operator", "IN");
        predicate.put("values", new ArrayList<>(List.of(new java.util.LinkedHashMap<>(value))));
        Map<String, Object> filter = new java.util.LinkedHashMap<>();
        filter.put("op", "FILTER");
        filter.put("predicates", new ArrayList<>(List.of(predicate)));
        docs.operations("detalle").add(1, filter);
        docs.reseal();
        return docs;
    }

    // ------------------------------------------------------------------ helpers

    private static String analyst() {
        return remember(token(TENANT, ANALYST, "HUMAN", "REPORT_RUNNER"));
    }

    private static String remember(String token) {
        TOKENS.add(token);
        return token;
    }

    private synchronized void registered() throws Exception {
        if (registered) {
            return;
        }
        String steward = remember(token(TENANT, "steward-b10", "HUMAN", "REPORTS_TENANCY_ADMIN"));
        String approver = remember(token(TENANT, "approver-b10", "HUMAN", "REPORTS_TENANCY_ADMIN"));
        byte[] created = mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + steward)
                        .contentType(MediaType.APPLICATION_JSON).content(RunApiSetup.BENCH_SCHEMAS))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        String id = (String) ContractFixtures.READER.readObject(created, "registration").get("id");
        mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations/" + id + "/approval")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver)).andExpect(status().isCreated());
        registered = true;
        RunProbes.resetCounts();
    }

    private ResultActions run(String token, UUID reportPlan) throws Exception {
        return run(token, SOURCE, reportPlan);
    }

    private ResultActions run(String token, UUID source, UUID reportPlan) throws Exception {
        var request = post("/api/v1/data-sources/" + source + "/report-plans/" + reportPlan + "/runs");
        return mvc.perform(token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static Map<String, Object> json(ResultActions actions) throws Exception {
        return ContractFixtures.READER.readObject(actions.andReturn().getResponse().getContentAsByteArray(),
                "response");
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void problem(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** Nothing reached Semantic, the configuration or the credentials of a source, or the run registry. */
    private static void nothingReached(int runsBefore) throws Exception {
        assertThat(TestPlatform.calls()).isEmpty();
        assertThat(RunProbes.availabilityQueries()).isZero();
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(RunApiSetup.runs(TENANT)).isEqualTo(runsBefore);
    }

    /** Every value that must never leave a report or a request: in a text, none of them may appear. */
    private static void noLeak(String text, String where) {
        List<String> forbidden = new ArrayList<>(List.of(QUESTION, FILTER, "Ñandú", SEMANTIC_BODY, EXCEPTION, SECRET,
                TenantDatabase.PASSWORD));
        forbidden.addAll(RunApiSetup.wellNames());
        forbidden.addAll(TOKENS);
        for (String value : forbidden) {
            assertThat(text).as(where + " must not contain a sentinel, a row value or a secret").doesNotContain(value);
        }
        assertThat(JWT.matcher(text).find()).as(where + " must not contain a token").isFalse();
    }

    private static long filesIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(directory)) {
            return s.count();
        }
    }

    // ------------------------------------------------------------------ T-9: validity (R4-8)

    /**
     * STALE before reading: no read starts, so no connection to the source is opened. B10-O3 (DS-20): the
     * destination is looked up in step 2 without its secret (a source without configuration is still answered 409
     * without contacting Semantic), and the credentials are no longer resolved: that happens only right before a
     * read. Physical sessions are counted in {@link #t9OnlyARunThatReadsOpensAPhysicalConnectionToTheBench}.
     */
    @Test
    void t9AStaleReportPlanBeforeReadingOpensNoConnection() throws Exception {
        registered();

        Map<String, Object> doc = json(run(analyst(), RP_STALE_BEFORE).andExpect(status().isCreated()));

        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("plan_stale");
        assertThat(l(doc.get("datasets"))).isEmpty();
        assertThat(doc.get("artifact")).isNull();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isZero();
    }

    /**
     * B10-O3 (D-O3-4): physical sessions to the bench, counted by PostgreSQL ({@link BenchSessions}), under
     * controlled conditions: a source that no test has used (no pool, so no idle connection to reuse), the
     * cumulative counter read only once settled, and the backends started since the run. A STALE run before reading
     * establishes none. The control proves the measures see a connection: the same kind of run on another unused
     * source, CURRENT and without tenancy, opens one (the tenancy is checked inside the read transaction, A4-B5) and
     * is refused {@code tenant_scope_unverified} without reading any row.
     */
    @Test
    void t9OnlyARunThatReadsOpensAPhysicalConnectionToTheBench() throws Exception {
        long before = BenchSessions.settledSessions();
        String since = BenchSessions.now();
        RunProbes.resetCounts();

        Map<String, Object> stale = json(run(analyst(), STALE_SOURCE, RP_PHYSICAL_STALE)
                .andExpect(status().isCreated()));

        assertThat(m(stale.get("refusal")).get("code")).isEqualTo("plan_stale");
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(BenchSessions.backendsStartedSince(since)).as("backends opened by a STALE run").isZero();
        long afterStale = BenchSessions.settledSessions();
        assertThat(afterStale).as("sessions established to the bench by a STALE run").isEqualTo(before);

        String sinceControl = BenchSessions.now();
        RunProbes.resetCounts();

        Map<String, Object> control = json(run(analyst(), CONTROL_SOURCE, RP_PHYSICAL_CONTROL)
                .andExpect(status().isCreated()));

        assertThat(m(control.get("refusal")).get("code")).isEqualTo("tenant_scope_unverified");
        assertThat(l(control.get("datasets"))).isEmpty();
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isEqualTo(1);
        assertThat(RunProbes.readsStarted()).isEqualTo(1);
        assertThat(BenchSessions.backendsStartedSince(sinceControl)).as("backends opened by a run that reads")
                .isGreaterThanOrEqualTo(1);
        assertThat(BenchSessions.settledSessions()).as("sessions established by a run that reads")
                .isGreaterThan(afterStale);
    }

    @Test
    void t9AReportPlanThatBecomesStaleWhileReadingDiscardsTheRows() throws Exception {
        registered();
        long staged = filesIn(storage.resolve("staging"));

        Map<String, Object> doc = json(run(analyst(), RP_STALE_AFTER).andExpect(status().isCreated()));
        UUID runId = UUID.fromString((String) doc.get("runId"));

        assertThat(RunProbes.readsStarted()).isEqualTo(1);
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).isEqualTo(1);
        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("plan_changed_during_execution");
        assertThat(l(doc.get("datasets"))).isEmpty();
        assertThat(doc.get("artifact")).isNull();
        assertThat(RunApiSetup.events(runId)).doesNotContain("ARTIFACT_STORED");
        assertThat(filesIn(storage.resolve("staging"))).isEqualTo(staged);
        problem(mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId + "/artifact")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())), 404, "not_found");
    }

    /** The approved code is {@code plan_validity_unconfirmed} (D-B10-2; the v3 names it validity_unverifiable). */
    @Test
    void t9ValidityThatSemanticCannotConfirmAfterReadingIsARefusedRun() throws Exception {
        registered();

        Map<String, Object> doc = json(run(analyst(), RP_SILENT_AFTER).andExpect(status().isCreated()));

        assertThat(RunProbes.readsStarted()).isEqualTo(1);
        assertThat(doc.get("status")).isEqualTo("REFUSED");
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("plan_validity_unconfirmed");
        assertThat(l(doc.get("datasets"))).isEmpty();
        assertThat(doc.get("artifact")).isNull();
    }

    // ------------------------------------------------------------------ T-11: no body

    @Test
    void t11ABodyIsRefusedBeforeSemanticTheCredentialsOrTheRegistryAreReached() throws Exception {
        registered();
        int runs = RunApiSetup.runs(TENANT);

        problem(mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sql\":\"select 1\"}")), 400, "malformed_request");
        problem(mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())
                .contentType(MediaType.TEXT_PLAIN).content(" ")), 400, "malformed_request");

        nothingReached(runs);
    }

    @Test
    void t11AuthenticationAndAuthorizationKeepTheirPrecedenceOverTheBodyCheck() throws Exception {
        int runs = RunApiSetup.runs(TENANT);
        String body = "{\"x\":1}";

        mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        problem(mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + remember(token(TENANT, ANALYST, "HUMAN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)), 403, "forbidden");
        problem(mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + remember(token(TENANT, ANALYST, "AI", "REPORT_RUNNER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)), 403, "forbidden");

        nothingReached(runs);
    }

    @Test
    void t11WithoutABodyOrWithAnEmptyOneTheRunIsNormal() throws Exception {
        registered();

        assertThat(json(run(analyst(), RP).andExpect(status().isCreated())).get("status")).isEqualTo("COMPLETED");
        assertThat(json(mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())
                .header(HttpHeaders.CONTENT_LENGTH, "0")).andExpect(status().isCreated())).get("status"))
                .isEqualTo("COMPLETED");
    }

    /** Over a real socket: a body sent in chunks (no Content-Length) is refused; an empty chunked one is not. */
    @Test
    void t11ABodySentInChunksIsRefusedOverHttp() throws Exception {
        registered();
        int runs = RunApiSetup.runs(TENANT);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP
                + "/runs");

        HttpResponse<String> chunked = client.send(HttpRequest.newBuilder(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(
                        "{\"sql\":\"select 1\"}".getBytes(StandardCharsets.UTF_8)))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(chunked.statusCode()).isEqualTo(400);
        assertThat(chunked.body()).contains("\"malformed_request\"");
        nothingReached(runs);

        HttpResponse<String> emptyChunked = client.send(HttpRequest.newBuilder(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[0]))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(emptyChunked.statusCode()).isEqualTo(201);
        assertThat(RunApiSetup.runs(TENANT)).isEqualTo(runs + 1);
    }

    // ------------------------------------------------------------------ T-5: security without side effects

    @Test
    void t5WithoutAValidTokenNothingIsReached() throws Exception {
        int runs = RunApiSetup.runs(TENANT);
        UUID someRun = UUID.randomUUID();
        List<String> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(TestPlatform.tokenWithAudience(TENANT, TestPlatform.SEMANTIC_AUDIENCE));
        invalid.add(TestPlatform.untrustedToken(TENANT));
        invalid.add(TestPlatform.expiredUserToken(TENANT));

        for (String token : invalid) {
            for (var request : List.of(
                    post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + RP + "/runs"),
                    get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + someRun),
                    get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + someRun + "/artifact"),
                    get("/api/v1/capabilities"))) {
                mvc.perform(token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isUnauthorized());
            }
        }

        nothingReached(runs);
    }

    @Test
    void t5WithoutThePermissionOrAsAnAiPrincipalNothingIsReached() throws Exception {
        int runs = RunApiSetup.runs(TENANT);
        UUID someRun = UUID.randomUUID();

        for (String token : List.of(remember(token(TENANT, ANALYST, "HUMAN")),
                remember(token(TENANT, ANALYST, "SERVICE", "REPORTS_TENANCY_ADMIN")),
                remember(token(TENANT, ANALYST, "AI", "REPORT_RUNNER", "REPORTS_TENANCY_ADMIN")))) {
            problem(run(token, RP), 403, "forbidden");
            problem(mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + someRun)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "forbidden");
            problem(mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + someRun + "/artifact")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "forbidden");
        }

        nothingReached(runs);
    }

    @Test
    void t5ARunStaysWithItsOwnerAndTenantAndReadingItReachesNoOtherService() throws Exception {
        registered();
        UUID runId = UUID.fromString((String) json(run(analyst(), RP).andExpect(status().isCreated())).get("runId"));
        RunProbes.reset();

        for (String other : List.of(remember(token(TENANT, "otra-persona", "HUMAN", "REPORT_RUNNER")),
                remember(token(OTHER_TENANT, ANALYST, "HUMAN", "REPORT_RUNNER")))) {
            problem(mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)), 404, "not_found");
            problem(mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId + "/artifact")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)), 404, "not_found");
        }
        mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())).andExpect(status().isOk());

        assertThat(TestPlatform.calls()).isEmpty();
        assertThat(RunProbes.availabilityQueries()).isZero();
        assertThat(RunProbes.credentialsResolved()).isZero();
        assertThat(RunProbes.readsStarted()).isZero();
    }

    /** COMPLETED (with a question and a filter value), PARTIAL and a download: no value or secret leaves them. */
    @Test
    void t5SuccessfulAndPartialRunsLeaveNoQuestionFilterValueRowOrSecretInLogsResponsesOrStore(
            CapturedOutput output) throws Exception {
        registered();
        List<String> responses = new ArrayList<>();

        ResultActions sentinels = run(analyst(), RP_SENTINELS).andExpect(status().isCreated());
        assertThat(json(sentinels).get("status")).isEqualTo("COMPLETED");
        responses.add(body(sentinels));
        ResultActions plain = run(analyst(), RP).andExpect(status().isCreated());
        Map<String, Object> plainDoc = json(plain);
        assertThat(plainDoc.get("status")).isEqualTo("COMPLETED");
        responses.add(body(plain));
        mvc.perform(get("/api/v1/data-sources/" + SOURCE + "/report-runs/" + plainDoc.get("runId") + "/artifact")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst())).andExpect(status().isOk());
        ResultActions partial = run(analyst(), RP_PARTIAL).andExpect(status().isCreated());
        assertThat(json(partial).get("status")).isEqualTo("PARTIAL");
        responses.add(body(partial));

        responses.forEach(r -> noLeak(r, "a response"));
        noLeak(output.getAll(), "the logs");
        noLeak(RunApiSetup.storedAbout(TENANT), "the run registry");
        assertThat(output.getAll()).contains("recorded as COMPLETED", "recorded as PARTIAL");
    }

    /** REFUSED and FAILED runs, with external messages and an unexpected exception carrying values. */
    @Test
    void t5RefusedAndFailedRunsLeaveNoExternalMessageOrExceptionMessage(CapturedOutput output) throws Exception {
        registered();
        List<String> responses = new ArrayList<>();

        responses.add(body(run(analyst(), RP_STALE_BEFORE).andExpect(status().isCreated())));
        responses.add(body(run(analyst(), RP_SILENT_AFTER).andExpect(status().isCreated())));
        responses.add(body(run(analyst(), RP_TWO_SECTIONS).andExpect(status().isCreated())));
        ResultActions failed = run(analyst(), RP_FAIL_AFTER);
        problem(failed, 500, "run_failed");
        failed.andExpect(header().exists(ReportRunController.RUN_ID_HEADER));
        responses.add(body(failed));
        UUID failedRun = UUID.fromString(failed.andReturn().getResponse().getHeader(ReportRunController.RUN_ID_HEADER));

        responses.forEach(r -> noLeak(r, "a response"));
        noLeak(output.getAll(), "the logs");
        noLeak(RunApiSetup.storedAbout(TENANT), "the run registry");
        assertThat(RunApiSetup.outcome(failedRun)).isEqualTo("FAILED");
        // Not vacuous: the failure was logged, with its class and stack frames but without its message.
        assertThat(output.getAll()).contains("failed internally", "message withheld; java.lang.IllegalStateException",
                "at com.nexora.reports.");
    }

    /** Failures before a run starts (D-A4-5): no run, and nothing external or unexpected leaks. */
    @Test
    void t5FailuresBeforeARunStartsLeaveNoRunAndNoExternalOrExceptionMessage(CapturedOutput output)
            throws Exception {
        registered();
        int runs = RunApiSetup.runs(TENANT);
        List<String> responses = new ArrayList<>();

        ResultActions down = run(analyst(), RP_SEMANTIC_DOWN);
        problem(down, 503, "semantic_unavailable");
        responses.add(body(down));
        ResultActions garbage = run(analyst(), RP_GARBAGE).andExpect(status().is5xxServerError());
        responses.add(body(garbage));
        ResultActions unexpected = run(analyst(), RP_FAIL_BEFORE);
        problem(unexpected, 500, "internal_error");
        responses.add(body(unexpected));

        assertThat(RunApiSetup.runs(TENANT)).isEqualTo(runs);
        responses.forEach(r -> noLeak(r, "a response"));
        noLeak(output.getAll(), "the logs");
        assertThat(output.getAll()).contains("unexpected error", "message withheld; java.lang.IllegalStateException");
    }

    /** What Reports stores about runs is structured: identifiers, counts, flags and codes, under a closed set. */
    @Test
    void t5TheRunRegistryHoldsOnlyItsClosedSetOfFields() throws Exception {
        registered();
        run(analyst(), RP).andExpect(status().isCreated());
        run(analyst(), RP_PARTIAL).andExpect(status().isCreated());

        assertThat(RunApiSetup.outcomeKeys(TENANT, "datasets")).isSubsetOf("datasetId", "planId", "planFingerprint",
                "rowCount", "truncated", "maxRows", "durationMs");
        assertThat(RunApiSetup.outcomeKeys(TENANT, "warnings")).isSubsetOf("code", "datasetId", "output",
                "delivered", "limit");
        noLeak(RunApiSetup.storedAbout(TENANT), "the run registry");
    }

    // ------------------------------------------------------------------ T-10 over HTTP and D-B10-4

    @Test
    void t10TheCapabilitiesAreReadByAnyAuthenticatedPrincipalOfATenantAndAreTheContractDocument()
            throws Exception {
        for (String token : List.of(remember(token(TENANT, "persona-sin-roles", "HUMAN")),
                remember(token(TENANT, "servicio", "SERVICE")),
                remember(token(TENANT, "intelligence", "AI")))) {
            byte[] bytes = mvc.perform(get("/api/v1/capabilities").header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Map<String, Object> doc = ContractFixtures.READER.readObject(bytes, "capabilities");

            assertThat(RunContractSchemas.capabilities(ContractFixtures.READER).firstError(doc)).isEmpty();
            assertThat(CanonicalJson.write(doc)).isEqualTo(CanonicalJson.write(capabilities.document()));
            assertThat(doc.get("service")).isEqualTo("reports");
        }
        // Reading the capabilities grants nothing else: the technical identity of Intelligence still cannot run.
        problem(run(remember(token(TENANT, "intelligence", "AI", "REPORT_RUNNER")), RP), 403, "forbidden");
        assertThat(TestPlatform.calls()).isEmpty();
        assertThat(RunProbes.availabilityQueries()).isZero();
        assertThat(RunProbes.credentialsResolved()).isZero();
    }

    @Test
    void t10TheCapabilitiesAreNeverAnonymousNorTenantless() throws Exception {
        mvc.perform(get("/api/v1/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/capabilities").header(HttpHeaders.AUTHORIZATION, "Bearer "
                + TestPlatform.tokenWithAudience(TENANT, TestPlatform.SEMANTIC_AUDIENCE)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/capabilities").header(HttpHeaders.AUTHORIZATION, "Bearer "
                + TestPlatform.userToken(null, "HUMAN", "REPORT_RUNNER"))).andExpect(status().isUnauthorized());
    }

    /**
     * D-B10-4 over HTTP, before anything is read: a logical plan with more JOIN operations than announced (the new
     * control) is refused as not supported; a specification with more sections than announced is refused by the
     * schema of nexora-report-spec/1, which already limited it.
     */
    @Test
    void whatIsNotAnnouncedIsARefusedRunWithoutReading() throws Exception {
        registered();

        Map<String, Object> joins = json(run(analyst(), RP_THREE_JOINS).andExpect(status().isCreated()));
        Map<String, Object> sections = json(run(analyst(), RP_TWO_SECTIONS).andExpect(status().isCreated()));

        assertThat(joins.get("status")).isEqualTo("REFUSED");
        assertThat(m(joins.get("refusal")).get("code")).isEqualTo("plan_not_supported");
        assertThat(sections.get("status")).isEqualTo("REFUSED");
        assertThat(m(sections.get("refusal")).get("code")).isEqualTo("contract_invalid");
        assertThat(RunProbes.readsStarted()).isZero();
    }
}
