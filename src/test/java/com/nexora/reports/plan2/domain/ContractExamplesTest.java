package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.infrastructure.ClasspathContractSchemas;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B2 (R4-10): Reports gives every example of the contracts it reads the same verdict as the reference validators
 * of {@code nexora-contracts} (manifest), and recomputes every declared fingerprint byte for byte.
 */
class ContractExamplesTest {

    private static final PlanV2Validator VALIDATOR = new PlanV2Validator(ContractFixtures.schemas(),
            ReportsCeilings.DEFAULTS, ContractFixtures.SHA256);

    @Test
    void theCopiedContractsAreUnaltered() throws Exception {
        Map<String, String> manifest = manifest();
        assertThat(manifest).hasSize(38);
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            assertThat(sha256(ContractFixtures.bytes(ContractFixtures.ROOT + entry.getKey())))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    void theSchemasUsedInProductionAreTheContractSchemas() throws Exception {
        Map<String, String> manifest = manifest();
        for (String resource : List.of(ClasspathContractSchemas.REPORT_SPEC, ClasspathContractSchemas.LOGICAL_PLAN,
                ClasspathContractSchemas.REPORT_PLAN)) {
            String relative = resource.substring("contracts/".length());
            assertThat(sha256(ContractFixtures.bytes(resource))).as(resource).isEqualTo(manifest.get(relative));
        }
    }

    static Stream<Arguments> examples() {
        Map<String, Object> manifest = ContractFixtures.read("examples/manifest.json");
        return l(manifest.get("examples")).stream().map(ContractFixtures::m)
                .filter(e -> contract((String) e.get("contract")) != null)
                .map(e -> Arguments.of(Named.of((String) e.get("file"), e)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void theVerdictOfEachExampleIsTheOneOfTheManifest(Map<String, Object> example) {
        String expected = "valid".equals(example.get("expect")) ? "OK" : (String) example.get("reason");
        Map<String, Object> document = ContractFixtures.read("examples/" + example.get("file"));

        assertThat(VALIDATOR.contractVerdict(contract((String) example.get("contract")), document))
                .isEqualTo(expected);
    }

    @Test
    void everyValidPlanAndReportPlanHasItsDeclaredFingerprint() {
        for (String file : List.of("pa-a.report-plan.json", "pa-a.resumen.logical-plan.json",
                "pa-a.total.logical-plan.json", "pa-a.detalle.logical-plan.json",
                "pa-a.gobernado.resumen.logical-plan.json")) {
            Map<String, Object> document = ContractFixtures.read("examples/valid/" + file);
            assertThat(CanonicalJson.fingerprintOf(document, ContractFixtures.SHA256)).as(file).isEqualTo(document.get("fingerprint"));
        }
    }

    @Test
    void theSpecificationFingerprintIsTheHashOfTheWholeSpecification() {
        Map<String, Object> reportPlan = ContractFixtures.read("examples/valid/pa-a.report-plan.json");
        Map<String, Object> governed = ContractFixtures.read("examples/valid/pa-a.gobernado.resumen.logical-plan.json");

        assertThat(CanonicalJson.sha256(ContractFixtures.read("examples/valid/pa-a.report-spec.json"),
                ContractFixtures.SHA256))
                .isEqualTo(reportPlan.get("specFingerprint"));
        assertThat(CanonicalJson.sha256(ContractFixtures.read("examples/valid/pa-a.gobernado.report-spec.json"),
                ContractFixtures.SHA256))
                .isEqualTo(governed.get("specFingerprint"));
        for (Object ref : l(reportPlan.get("plans"))) {
            Map<String, Object> plan = ContractFixtures.read("examples/valid/pa-a." + m(ref).get("datasetId")
                    + ".logical-plan.json");
            assertThat(plan.get("fingerprint")).isEqualTo(m(ref).get("planFingerprint"));
            assertThat(plan.get("specFingerprint")).isEqualTo(reportPlan.get("specFingerprint"));
        }
    }

    private static PlanV2Validator.Contract contract(String name) {
        return switch (name) {
            case "report-spec" -> PlanV2Validator.Contract.REPORT_SPEC;
            case "logical-plan" -> PlanV2Validator.Contract.LOGICAL_PLAN;
            case "report-plan" -> PlanV2Validator.Contract.REPORT_PLAN;
            default -> null;
        };
    }

    private static Map<String, String> manifest() {
        String text = new String(ContractFixtures.bytes(ContractFixtures.ROOT + "CONTRACTS.sha256"),
                StandardCharsets.UTF_8);
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                entries.put(line.substring(66).strip(), line.substring(0, 64));
            }
        }
        return entries;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
