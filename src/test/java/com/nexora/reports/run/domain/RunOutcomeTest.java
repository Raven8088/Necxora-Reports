package com.nexora.reports.run.domain;

import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ReportResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B8 (adjustment 1): RUN-R1 extended to every real state of a run, checked before the database checks it too. */
class RunOutcomeTest {

    private static final String FP = "a".repeat(64);

    @Test
    void anAssembledResultIsCompletedOrPartialWithItsEvidence() {
        ReportResult complete = RenderFixtures.paA();
        RunOutcome completed = RunOutcome.of(complete);
        assertThat(completed.kind()).isEqualTo(RunOutcomeKind.COMPLETED);
        assertThat(completed.reportPlanFingerprint()).isEqualTo(complete.run().reportPlanFingerprint());
        assertThat(completed.specFingerprint()).isEqualTo(complete.run().specFingerprint());
        assertThat(completed.snapshot()).isEqualTo("1234:1234:");
        assertThat(completed.datasets()).isEqualTo(complete.methodology().datasets());
        assertThat(completed.warnings()).isEmpty();

        RunOutcome partial = RunOutcome.of(RenderFixtures.truncatedPaA());
        assertThat(partial.kind()).isEqualTo(RunOutcomeKind.PARTIAL);
        assertThat(partial.warnings()).hasSize(1);
    }

    @Test
    void aLateRefusalOrFailureKeepsWhatIsKnown() {
        RunOutcome completed = RunOutcome.of(RenderFixtures.paA());
        RunOutcome quota = completed.withRefusal(RunOutcome.ARTIFACT_QUOTA_EXCEEDED);
        assertThat(quota.kind()).isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(quota.refusalCode()).isEqualTo("artifact_quota_exceeded");
        assertThat(quota.failureCode()).isNull();
        assertThat(quota.datasets()).isEqualTo(completed.datasets());
        assertThat(quota.snapshot()).isEqualTo(completed.snapshot());

        RunOutcome storage = completed.withFailure(FailureCode.STORAGE_FAILED);
        assertThat(storage.kind()).isEqualTo(RunOutcomeKind.FAILED);
        assertThat(storage.failureCode().code()).isEqualTo("storage_failed");
        assertThat(storage.refusalCode()).isNull();
        assertThat(storage.reportPlanFingerprint()).isEqualTo(completed.reportPlanFingerprint());
    }

    @Test
    void earlyOutcomesNeedNoEvidence() {
        assertThat(RunOutcome.refused("tenant_scope_unverified", null, null).kind())
                .isEqualTo(RunOutcomeKind.REFUSED);
        assertThat(RunOutcome.failed(FailureCode.INTERNAL_ERROR).failureCode()).isEqualTo(FailureCode.INTERNAL_ERROR);
        RunOutcome interrupted = RunOutcome.interrupted();
        assertThat(interrupted.refusalCode()).isNull();
        assertThat(interrupted.failureCode()).isNull();
        assertThat(interrupted.kind().hasArtifact()).isFalse();
    }

    @Test
    void onlyCompletedAndPartialHaveAnArtifact() {
        assertThat(RunOutcomeKind.COMPLETED.hasArtifact()).isTrue();
        assertThat(RunOutcomeKind.PARTIAL.hasArtifact()).isTrue();
        assertThat(RunOutcomeKind.REFUSED.hasArtifact()).isFalse();
        assertThat(RunOutcomeKind.FAILED.hasArtifact()).isFalse();
        assertThat(RunOutcomeKind.INTERRUPTED.hasArtifact()).isFalse();
    }

    @Test
    void partialIfAndOnlyIfADatasetIsTruncated() {
        List<Methodology.DatasetExecution> truncated = List.of(dataset(true));
        List<Methodology.DatasetExecution> complete = List.of(dataset(false));
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.COMPLETED, FP, FP, null, null, "1:1:", truncated,
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.PARTIAL, FP, FP, null, null, "1:1:", complete,
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RunOutcome(RunOutcomeKind.PARTIAL, FP, FP, null, null, "1:1:", truncated, List.of()).kind())
                .isEqualTo(RunOutcomeKind.PARTIAL);
    }

    @Test
    void aDeliveredRunHasItsFingerprintsAndSnapshotAndNoCode() {
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.COMPLETED, null, FP, null, null, "1:1:", List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.COMPLETED, FP, FP, null, null, null, List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.COMPLETED, FP, FP, "x", null, "1:1:", List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.COMPLETED, FP, FP, null, FailureCode.STORAGE_FAILED,
                "1:1:", List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusalsAndFailuresAreNeverMixed() {
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.REFUSED, null, null, null, null, null, List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.REFUSED, null, null, "x", FailureCode.STORAGE_FAILED,
                null, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.FAILED, null, null, null, null, null, List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.FAILED, null, null, "x", FailureCode.STORAGE_FAILED,
                null, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.INTERRUPTED, null, null, "x", null, null, List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codesFingerprintsAndSnapshotsHaveTheirFormat() {
        assertThatThrownBy(() -> RunOutcome.refused("Bad Code", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunOutcome.refused("x", "ABC", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOutcome(RunOutcomeKind.COMPLETED, FP, FP, null, null, "1:1", List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(FailureCode.of("render_failed")).isEqualTo(FailureCode.RENDER_FAILED);
        assertThatThrownBy(() -> FailureCode.of("disk_on_fire")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Methodology.DatasetExecution dataset(boolean truncated) {
        return new Methodology.DatasetExecution("d", UUID.randomUUID(), FP, 3, truncated, 3, 1);
    }
}
