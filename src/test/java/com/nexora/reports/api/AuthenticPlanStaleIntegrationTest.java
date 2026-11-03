package com.nexora.reports.api;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.support.AuthenticPlans;
import com.nexora.reports.support.BenchSessions;
import com.nexora.reports.support.RunApiSetup;
import com.nexora.reports.support.RunProbes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.support.RunApiSetup.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4-B10-c.1, E10: the authentic STALE case of B10-a ({@code caducado}, with Semantic's own STALE answer) is refused
 * before reading without resolving credentials and without any physical session to the bench, counted by PostgreSQL
 * ({@link BenchSessions}, as in B10-O3). This class has its own application context and registers no tenancy, so its
 * pool of the export's source does not exist until something reads: nothing idle could be reused.
 * <p>
 * Control: the authentic {@code pa-a} on the same source opens a session, which proves the measures see one. Whether
 * that read completes depends on whether {@link AuthenticPlanRunIntegrationTest} already approved the tenancy of this
 * source in Reports' database (Surefire's class order): it is either {@code COMPLETED} or refused
 * {@code tenant_scope_unverified} inside the read transaction (A4-B5); in both cases a connection was opened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RunProbes.class)
class AuthenticPlanStaleIntegrationTest {

    private static final AuthenticPlans PLANS = AuthenticPlans.load();
    private static final UUID TENANT = PLANS.tenantId();
    private static final UUID SOURCE = PLANS.dataSourceId();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        RunApiSetup.common(registry, Files.createTempDirectory("nexora-b10c1-stale"));
        RunApiSetup.account(registry, 0, TENANT, "reports-b10c1-stale", "secret-b10c1-stale");
        RunApiSetup.source(registry, 0, TENANT, SOURCE);
    }

    @Test
    void theAuthenticStaleCaseOpensNoPhysicalConnectionWhileAReadOpensOne() throws Exception {
        RunProbes.reset();
        PLANS.serve(TENANT, SOURCE);
        PLANS.validities(TENANT, SOURCE, "caducado", PLANS.of("caducado").validityFile());
        PLANS.validities(TENANT, SOURCE, "pa-a", PLANS.of("pa-a").validityFile());
        String analyst = token(TENANT, "analyst-b10c1-stale", "HUMAN", "REPORT_RUNNER");

        long before = BenchSessions.settledSessions();
        String since = BenchSessions.now();
        RunProbes.resetCounts();
        Map<String, Object> stale = run(analyst, PLANS.of("caducado").reportPlanId());

        assertThat(stale.get("status")).isEqualTo("REFUSED");
        assertThat(m(stale.get("refusal")).get("code")).isEqualTo("plan_stale");
        assertThat(RunProbes.availabilityQueries()).isEqualTo(1);
        assertThat(RunProbes.credentialsResolved()).as("credentials resolved for a STALE plan").isZero();
        assertThat(RunProbes.readsStarted()).isZero();
        assertThat(BenchSessions.backendsStartedSince(since)).as("backends opened by a STALE plan").isZero();
        long afterStale = BenchSessions.settledSessions();
        assertThat(afterStale).as("sessions established by a STALE plan").isEqualTo(before);

        String sinceControl = BenchSessions.now();
        RunProbes.resetCounts();
        Map<String, Object> control = run(analyst, PLANS.of("pa-a").reportPlanId());

        if ("REFUSED".equals(control.get("status"))) {
            assertThat(m(control.get("refusal")).get("code")).isEqualTo("tenant_scope_unverified");
            assertThat(l(control.get("datasets"))).isEmpty();
        } else {
            assertThat(control.get("status")).isEqualTo("COMPLETED");
        }
        assertThat(RunProbes.credentialsResolved()).isEqualTo(1);
        assertThat(RunProbes.readsStarted()).isEqualTo(1);
        assertThat(BenchSessions.backendsStartedSince(sinceControl)).as("backends opened by a read")
                .isGreaterThanOrEqualTo(1);
        assertThat(BenchSessions.settledSessions()).as("sessions established by a read").isGreaterThan(afterStale);
    }

    private Map<String, Object> run(String token, UUID reportPlan) throws Exception {
        byte[] body = mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/" + reportPlan + "/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        return ContractFixtures.READER.readObject(body, "report-run");
    }
}
