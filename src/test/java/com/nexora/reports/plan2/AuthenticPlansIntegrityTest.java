package com.nexora.reports.plan2;

import com.nexora.reports.plan2.domain.PlanV2Exception;
import com.nexora.reports.plan2.domain.PlanV2Violation;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.support.AuthenticPlans;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B10-c.1, E1: the authentic documents of B10-a are the archived ones (pin, SHA256SUMS, manifest) and Reports' real
 * validator (B2) and translator (B3) accept every one of them as they are, bound to the export's tenant and data
 * source and to no other. No document is edited or resealed.
 */
class AuthenticPlansIntegrityTest {

    private static final AuthenticPlans PLANS = AuthenticPlans.load();

    @Test
    void theCopyIsTheArchivedExportByItsPinAndHashes() {
        assertThat(PLANS.cases().keySet()).containsExactly("pa-a", "pa-a-gobernado", "empresas", "filtro-in",
                "filtro-numero", "filtro-nulo", "volumen", "caducado");
        assertThat(m(PLANS.manifest().get("files"))).hasSize(51);
        assertThat(PLANS.manifest().get("generatedBy"))
                .isEqualTo("com.nexora.semantic.api.ReportPlanExportIntegrationTest (mvn verify, Nexora-Semantic)");
        assertThat(PLANS.of("caducado").validityStatus()).isEqualTo("STALE");
        PLANS.cases().values().stream().filter(c -> !"caducado".equals(c.id()))
                .forEach(c -> assertThat(c.validityStatus()).as(c.id()).isEqualTo("CURRENT"));
    }

    @Test
    void aSingleChangedByteOrAWrongPinIsDetected() {
        byte[] pin = ContractFixtures.bytes(AuthenticPlans.PIN);
        String document = PLANS.of("empresas").reportPlanFile();
        assertThatThrownBy(() -> AuthenticPlans.verify(relative -> {
            byte[] bytes = AuthenticPlans.bytes(relative);
            if (relative.equals(document)) {
                bytes[bytes.length / 2] ^= 1;
            }
            return bytes;
        }, pin)).isInstanceOf(AssertionError.class).hasMessageContaining(document);
        byte[] wrongPin = new String(pin, java.nio.charset.StandardCharsets.UTF_8).replace("6e49f545", "6e49f546")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> AuthenticPlans.verify(AuthenticPlans::bytes, wrongPin))
                .isInstanceOf(AssertionError.class).hasMessageContaining("manifest.json");
    }

    @Test
    void everyCaseIsValidatedAndTranslatedByTheRealReportsAsItIs() {
        PlanV2Translator translator = new PlanV2Translator();
        for (AuthenticPlans.Case c : PLANS.cases().values()) {
            Map<String, Object> reportPlan = PLANS.reportPlan(c.id());
            Map<String, Object> spec = PLANS.spec(c.id());
            ValidatedReportPlan plan = TestPlans.VALIDATOR.validate(PLANS.tenantId(), PLANS.dataSourceId(),
                    reportPlan, spec, PLANS.plans(c.id()));
            SpecPresentation.from(spec, plan, ContractFixtures.SHA256);
            assertThat(plan.reportPlan().fingerprint()).as(c.id()).isEqualTo(c.reportPlanFingerprint());
            assertThat(plan.datasets()).as(c.id()).extracting(ValidatedReportPlan.Dataset::datasetId)
                    .containsExactlyElementsOf(c.planIds().keySet());
            for (ValidatedReportPlan.Dataset d : plan.datasets()) {
                assertThat(d.planId()).as(c.id() + "/" + d.datasetId()).isEqualTo(c.planIds().get(d.datasetId()));
                assertThat(translator.translate(d).sql()).as(c.id() + "/" + d.datasetId()).startsWith("SELECT ");
            }
        }
    }

    /** Bound to the export's tenant and data source: under any other the same bytes are an identity mismatch. */
    @Test
    void underAnotherTenantOrDataSourceTheDocumentsAreRefused() {
        for (List<UUID> ids : List.of(List.of(UUID.randomUUID(), PLANS.dataSourceId()),
                List.of(PLANS.tenantId(), UUID.randomUUID()))) {
            assertThatThrownBy(() -> TestPlans.VALIDATOR.validate(ids.get(0), ids.get(1), PLANS.reportPlan("pa-a"),
                    PLANS.spec("pa-a"), PLANS.plans("pa-a"))).isInstanceOf(PlanV2Exception.class)
                    .satisfies(e -> assertThat(((PlanV2Exception) e).first().kind())
                            .isEqualTo(PlanV2Violation.Kind.IDENTITY_MISMATCH));
        }
    }

    /** E14 (R4-26): the report B is another shape over other tables; nothing about it is in the engine. */
    @Test
    void empresasIsStructurallyDifferentFromPaA() {
        assertThat(operations("pa-a")).doesNotContain("JOIN");
        assertThat(operations("empresas")).contains("JOIN", "FILTER", "AGGREGATE");
        assertThat(tables("pa-a")).containsExactly("wm_well");
        assertThat(tables("empresas")).containsExactlyInAnyOrder("etp_company", "bkw_operators");
    }

    private static List<Object> operations(String caseId) {
        return PLANS.plans(caseId).values().stream().flatMap(p -> l(p.get("operations")).stream())
                .map(o -> m(o).get("op")).distinct().toList();
    }

    private static List<Object> tables(String caseId) {
        return PLANS.plans(caseId).values().stream().flatMap(p -> l(p.get("objects")).stream())
                .filter(o -> "TABLE".equals(m(o).get("type"))).map(o -> m(m(o).get("naturalKey")).get("name"))
                .distinct().toList();
    }
}
