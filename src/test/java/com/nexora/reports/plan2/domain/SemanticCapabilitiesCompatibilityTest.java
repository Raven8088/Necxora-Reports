package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.support.AuthenticPlans;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B10-c.1, E2: the capabilities that Nexora Semantic served in B10-a ({@code respuestas/capabilities.json} of the
 * authentic export) and those of Reports, compared dimension by dimension; and every authentic plan uses only what
 * both announce. The documented difference of contract lists (DS-21: Reports does not announce
 * {@code nexora-report-spec/1}) is stated, not hidden.
 */
class SemanticCapabilitiesCompatibilityTest {

    private static final Map<String, Object> SEMANTIC = AuthenticPlans.tree("respuestas/capabilities.json");
    private static final Map<String, Object> REPORTS = ReportsCapabilities.of("test", ReportsCeilings.DEFAULTS)
            .document();

    @Test
    void bothDocumentsAreValidCapabilitiesOfTheirService() {
        var schema = RunContractSchemas.capabilities(ContractFixtures.READER);
        assertThat(schema.firstError(SEMANTIC)).isEmpty();
        assertThat(schema.firstError(REPORTS)).isEmpty();
        assertThat(SEMANTIC.get("service")).isEqualTo("semantic");
        assertThat(REPORTS.get("service")).isEqualTo("reports");
    }

    @Test
    void thePlanContractsAreSharedAndTheSpecificationIsOnlyAnnouncedBySemantic() {
        List<Object> semantic = l(SEMANTIC.get("contracts"));
        List<Object> reports = l(REPORTS.get("contracts"));
        assertThat(semantic).contains("nexora-report-plan/1", "nexora-logical-plan/2");
        assertThat(reports).contains("nexora-report-plan/1", "nexora-logical-plan/2");
        // DS-21 (open): Reports reads nexora-report-spec/1 but does not announce it.
        assertThat(semantic).contains("nexora-report-spec/1");
        assertThat(reports).doesNotContain("nexora-report-spec/1");
    }

    @Test
    void theBasicAProfilesAreTheSameInEveryDimension() {
        Map<String, Object> semantic = new LinkedHashMap<>(profile(SEMANTIC));
        Map<String, Object> reports = new LinkedHashMap<>(profile(REPORTS));
        assertThat(semantic.keySet()).isEqualTo(reports.keySet());
        for (String dimension : semantic.keySet()) {
            Object a = semantic.get(dimension);
            Object b = reports.get(dimension);
            if (a instanceof List<?> la && b instanceof List<?> lb) {
                assertThat(new TreeSet<>(la.stream().map(String::valueOf).toList())).as(dimension)
                        .isEqualTo(new TreeSet<>(lb.stream().map(String::valueOf).toList()));
            } else if (a instanceof Number na && b instanceof Number nb) {
                assertThat(na.longValue()).as(dimension).isEqualTo(nb.longValue());
            } else {
                assertThat(a).as(dimension).isEqualTo(b);
            }
        }
    }

    /** Every authentic plan stays within what both services announce. */
    @Test
    void everyAuthenticPlanUsesOnlyWhatBothAnnounce() {
        Map<String, Object> profile = profile(REPORTS);
        Set<Object> operations = Set.copyOf(l(profile.get("planOperations")));
        Set<Object> operators = Set.copyOf(l(profile.get("filterOperators")));
        Set<Object> aggregations = Set.copyOf(l(profile.get("aggregations")));
        AuthenticPlans plans = AuthenticPlans.load();
        for (AuthenticPlans.Case c : plans.cases().values()) {
            for (Map<String, Object> plan : plans.plans(c.id()).values()) {
                for (Object o : l(plan.get("operations"))) {
                    Map<String, Object> op = m(o);
                    assertThat(operations).as(c.id()).contains(op.get("op"));
                    if ("FILTER".equals(op.get("op"))) {
                        l(op.get("predicates")).forEach(p -> assertThat(operators).contains(m(p).get("operator")));
                    }
                    if ("AGGREGATE".equals(op.get("op"))) {
                        l(op.get("measures")).forEach(x -> assertThat(aggregations).contains(m(x).get("aggregation")));
                    }
                    if ("LIMIT".equals(op.get("op"))) {
                        assertThat(((Number) op.get("maxRows")).longValue()).as(c.id())
                                .isLessThanOrEqualTo(((Number) profile.get("maxRowsPerPlan")).longValue());
                    }
                }
            }
        }
    }

    private static Map<String, Object> profile(Map<String, Object> capabilities) {
        List<Object> profiles = l(capabilities.get("profiles"));
        assertThat(profiles).hasSize(1);
        Map<String, Object> profile = m(profiles.getFirst());
        assertThat(profile.get("name")).isEqualTo("basic-a");
        return profile;
    }
}
