package com.nexora.reports.execution.application;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.infrastructure.RunContractSchemas;
import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ResultWarning;
import com.nexora.reports.run.domain.ArtifactState;
import com.nexora.reports.run.domain.FailureCode;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunOwner;
import com.nexora.reports.run.domain.RunState;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StoredArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B9 (D-B9-3): the documents Reports emits are validated against the real {@code nexora-report-run/1} and
 * {@code nexora-report-artifact/1} (copied byte for byte, separate manifest), and give every example of the contract
 * the verdict of its reference validator, RUN-R1 included.
 */
class ReportRunDocumentsTest {

    private static final ReportRunDocuments DOCUMENTS = ReportRunServiceTest.DOCUMENTS;
    private static final String FP = "15f5283c8c66a1a8ec449f25a5339d706071a354da50547e72f458515fbca4b1";
    private static final String SPEC_FP = "a".repeat(64);
    private static final UUID RUN = UUID.fromString("7a3f0000-0000-4000-8000-000000000091");
    private static final UUID PLAN = UUID.fromString("7a3f0000-0000-4000-8000-000000000080");
    private static final Instant STARTED = Instant.parse("2026-11-01T16:42:03.123456Z");
    private static final Instant FINISHED = Instant.parse("2026-11-01T16:42:05Z");

    @Test
    void theRunContractsAreTheContractFilesByteForByte() throws Exception {
        String manifest = new String(ContractFixtures.bytes(ContractFixtures.ROOT + "RUN-CONTRACTS.sha256"),
                StandardCharsets.UTF_8);
        int lines = 0;
        for (String line : manifest.lines().filter(x -> !x.isBlank()).toList()) {
            String[] parts = line.split("\\s+", 2);
            assertThat(sha256(ContractFixtures.bytes(ContractFixtures.ROOT + parts[1]))).as(parts[1])
                    .isEqualTo(parts[0]);
            lines++;
        }
        assertThat(lines).isEqualTo(9);
        assertThat(manifest).contains("c08771ec0c937603dd153ecacac5b166eae85074137714e88e82d0b6a018d3ee");
        assertThat(manifest).contains("4ebdade9e615d66ba623d5fc9afd897492a9d84c54abb296ddfc871d19af551c");
    }

    @Test
    void theSchemasUsedInProductionAreTheContractSchemas() throws Exception {
        assertThat(sha256(ContractFixtures.bytes(RunContractSchemas.REPORT_RUN)))
                .isEqualTo("c08771ec0c937603dd153ecacac5b166eae85074137714e88e82d0b6a018d3ee");
        assertThat(sha256(ContractFixtures.bytes(RunContractSchemas.REPORT_ARTIFACT)))
                .isEqualTo("4ebdade9e615d66ba623d5fc9afd897492a9d84c54abb296ddfc871d19af551c");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "examples/valid/pa-a.report-run.json, run, OK",
            "examples/valid/volumen.report-run.partial.json, run, OK",
            "examples/valid/pa-a.report-run.refused.json, run, OK",
            "examples/valid/pa-a.report-artifact.json, artifact, OK",
            "examples/invalid/run-varias-transacciones.json, run, SCHEMA",
            "examples/invalid/run-truncado-como-completo.json, run, RUN-R1",
            "examples/invalid/run-rechazado-con-archivo.json, run, RUN-R1"})
    void everyExampleOfTheContractGetsTheVerdictOfItsReferenceValidator(String file, String contract,
                                                                          String expected) {
        Map<String, Object> document = ContractFixtures.read(file);

        String verdict = "run".equals(contract) ? DOCUMENTS.runVerdict(document)
                : DOCUMENTS.artifactVerdict(document);

        assertThat(verdict).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ built documents

    private static StoredArtifact artifact() {
        return new StoredArtifact(UUID.fromString("7a3f0000-0000-4000-8000-000000000090"), RUN, UUID.randomUUID(),
                UUID.randomUUID(), RenderedArtifact.XLSX_MEDIA_TYPE, RenderedArtifact.EXCEL_CONTRACT, "f".repeat(64),
                18_432, List.of("Reporte", "Listado", "Metodología"), FINISHED, FINISHED.plusSeconds(7 * 86_400));
    }

    private static List<Methodology.DatasetExecution> datasets(boolean truncated) {
        return List.of(new Methodology.DatasetExecution("resumen", PLAN, "b".repeat(64), 15, false, 10_000, 12),
                new Methodology.DatasetExecution("detalle", PLAN, "c".repeat(64), truncated ? 3 : 35, truncated,
                        truncated ? 3 : 10_000, 9));
    }

    private static RunView view(RunOutcome outcome, boolean withArtifact) {
        Optional<StoredArtifact> artifact = withArtifact ? Optional.of(artifact()) : Optional.empty();
        return new RunView(RUN, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new RunOwner("analyst-1", RunOwner.Type.HUMAN), STARTED, STARTED.plusSeconds(120),
                outcome == null ? RunState.IN_PROGRESS : RunState.of(Optional.of(outcome.kind()), false),
                Optional.ofNullable(outcome), outcome == null ? Optional.empty() : Optional.of(FINISHED), artifact,
                artifact.map(a -> ArtifactState.AVAILABLE));
    }

    @Test
    void aCompletedRunIsAValidDocumentWithItsArtifact() {
        RunOutcome outcome = new RunOutcome(RunOutcomeKind.COMPLETED, FP, SPEC_FP, null, null, "7841:7841:",
                datasets(false), List.of());

        Map<String, Object> doc = DOCUMENTS.reportRun(view(outcome, true)).orElseThrow();

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(doc.get("startedAt")).isEqualTo("2026-11-01T16:42:03.123456Z");
        assertThat(doc.get("finishedAt")).isEqualTo("2026-11-01T16:42:05Z");
        assertThat(m(doc.get("consistency")).get("snapshot")).isEqualTo("7841:7841:");
        assertThat(m(doc.get("artifact")).get("sheets")).isEqualTo(List.of("Reporte", "Listado", "Metodología"));
        assertThat(DOCUMENTS.artifactVerdict(m(doc.get("artifact")))).isEqualTo("OK");
        assertThat(m(l(doc.get("datasets")).getFirst()).containsKey("planFingerprint")).isFalse();
    }

    @Test
    void aPartialRunCarriesItsTruncationWarning() {
        RunOutcome outcome = new RunOutcome(RunOutcomeKind.PARTIAL, FP, SPEC_FP, null, null, "1:1:",
                datasets(true), List.of(new ResultWarning(ResultWarning.DATASET_TRUNCATED, "detalle", null, 3, 3),
                new ResultWarning(ResultWarning.CELL_TEXT_TRUNCATED, "detalle", "nombre", 2, 32_767)));

        Map<String, Object> doc = DOCUMENTS.reportRun(view(outcome, true)).orElseThrow();

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(l(doc.get("warnings"))).hasSize(2);
        assertThat((String) m(l(doc.get("warnings")).get(1)).get("message")).doesNotContain("nombre");
    }

    @Test
    void aRefusalBeforeReadingHasNoSnapshotNoDatasetsAndNoArtifact() {
        Map<String, Object> doc = DOCUMENTS.reportRun(view(RunOutcome.refused("tenant_scope_unverified", FP, null),
                false)).orElseThrow();

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(m(doc.get("consistency")).containsKey("snapshot")).isFalse();
        assertThat(l(doc.get("datasets"))).isEmpty();
        assertThat(m(doc.get("refusal")).get("code")).isEqualTo("tenant_scope_unverified");
        assertThat((String) m(doc.get("refusal")).get("message")).isNotEmpty();
        assertThat(doc.containsKey("artifact")).isTrue();
        assertThat(doc.get("artifact")).isNull();
    }

    @Test
    void aRefusalAfterReadingKeepsWhatWasKnown() {
        RunOutcome outcome = new RunOutcome(RunOutcomeKind.COMPLETED, FP, SPEC_FP, null, null, "9:9:",
                datasets(false), List.of()).withRefusal(RunOutcome.ARTIFACT_QUOTA_EXCEEDED);

        Map<String, Object> doc = DOCUMENTS.reportRun(view(outcome, false)).orElseThrow();

        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("OK");
        assertThat(l(doc.get("datasets"))).hasSize(2);
        assertThat(m(doc.get("consistency")).get("snapshot")).isEqualTo("9:9:");
    }

    @Test
    void anUnknownRefusalCodeStillGetsAMessage() {
        assertThat(ReportRunDocuments.refusalMessage("something_new")).contains("something_new");
    }

    @Test
    void internalStatesAndRunsWithoutOutcomeHaveNoReportRunDocument() {
        assertThat(DOCUMENTS.reportRun(view(null, false))).isEmpty();
        assertThat(DOCUMENTS.reportRun(view(RunOutcome.failed(FailureCode.RENDER_FAILED), false))).isEmpty();
        assertThat(DOCUMENTS.reportRun(view(RunOutcome.interrupted(), false))).isEmpty();
        assertThat(DOCUMENTS.reportRun(view(RunOutcome.refused("contract_invalid", null, null), false))).isEmpty();
    }

    @Test
    void aDocumentThatWouldNotValidateIsNeverReturned() {
        // A delivered run whose artifact is missing from the registry cannot be described: fail closed.
        RunOutcome outcome = new RunOutcome(RunOutcomeKind.COMPLETED, FP, SPEC_FP, null, null, "1:1:",
                datasets(false), List.of());
        assertThatThrownBy(() -> DOCUMENTS.reportRun(view(outcome, false))).isInstanceOf(IllegalStateException.class);
        // A dataset id outside the contract's pattern is refused by the schema.
        RunOutcome odd = new RunOutcome(RunOutcomeKind.COMPLETED, FP, SPEC_FP, null, null, "1:1:",
                List.of(new Methodology.DatasetExecution("Detalle-1", PLAN, "b".repeat(64), 1, false, 10, 1)),
                List.of());
        assertThatThrownBy(() -> DOCUMENTS.reportRun(view(odd, true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runR1IsCheckedBesidesTheSchema() {
        Map<String, Object> doc = new LinkedHashMap<>(DOCUMENTS.reportRun(view(new RunOutcome(
                RunOutcomeKind.COMPLETED, FP, SPEC_FP, null, null, "1:1:", datasets(false), List.of()), true))
                .orElseThrow());
        doc.put("status", "PARTIAL");
        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("RUN-R1");
        doc.put("status", "REFUSED");
        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("RUN-R1");
        doc.put("status", "FAILED");
        assertThat(DOCUMENTS.runVerdict(doc)).isEqualTo("SCHEMA");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
