package com.nexora.reports.run.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B8: states derived from the clock, owners, starts, scopes, limits and the shapes of the store's answers. */
class RunDomainTest {

    private static final UUID ID = UUID.fromString("b8000000-0000-4000-8000-000000000001");

    @Test
    void theRunStateIsTheOutcomeOrInProgressUntilTheDeadline() {
        assertThat(RunState.of(Optional.empty(), false)).isEqualTo(RunState.IN_PROGRESS);
        assertThat(RunState.of(Optional.empty(), true)).isEqualTo(RunState.INTERRUPTED);
        for (RunOutcomeKind kind : RunOutcomeKind.values()) {
            assertThat(RunState.of(Optional.of(kind), false).name()).isEqualTo(kind.name());
            assertThat(RunState.of(Optional.of(kind), true).name()).isEqualTo(kind.name());
        }
    }

    @Test
    void theArtifactStateIsDerivedFromItsContentAndExpiry() {
        assertThat(ArtifactState.of(true, false)).isEqualTo(ArtifactState.AVAILABLE);
        assertThat(ArtifactState.of(true, true)).isEqualTo(ArtifactState.EXPIRED);
        assertThat(ArtifactState.of(false, true)).isEqualTo(ArtifactState.PURGED);
        assertThat(ArtifactState.of(false, false)).isEqualTo(ArtifactState.PURGED);
    }

    @Test
    void anOwnerIsAPersonOrAServiceWithASubject() {
        assertThat(new RunOwner("ana", RunOwner.Type.HUMAN).subject()).isEqualTo("ana");
        assertThatThrownBy(() -> new RunOwner(" ", RunOwner.Type.HUMAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunOwner("x".repeat(256), RunOwner.Type.SERVICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(RunOwner.Type.values())).containsExactly(RunOwner.Type.HUMAN, RunOwner.Type.SERVICE);
    }

    @Test
    void aStartHasABoundedDuration() {
        RunOwner owner = new RunOwner("ana", RunOwner.Type.HUMAN);
        RunStart start = new RunStart(ID, ID, ID, ID, owner, Duration.ofMinutes(5));
        assertThat(start.ref()).isEqualTo(new RunRef(ID, ID, ID));
        assertThatThrownBy(() -> new RunStart(ID, ID, ID, ID, owner, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunStart(ID, ID, ID, ID, owner, Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunScope(ID, ID, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStorageLimitsHaveDocumentedMaxima() {
        assertThat(StorageLimits.DEFAULTS.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(StorageLimits.DEFAULTS.tenantQuotaBytes()).isEqualTo(500L * 1024 * 1024);
        assertThat(StorageLimits.DEFAULTS.maxFileBytes()).isEqualTo(20L * 1024 * 1024);
        assertThatThrownBy(() -> new StorageLimits(Duration.ofMinutes(59), 1L << 30, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageLimits(Duration.ofDays(31), 1L << 30, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageLimits(Duration.ofDays(7), 6L << 30, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageLimits(Duration.ofDays(7), 1L << 30, 20L * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageLimits(Duration.ofDays(7), 1023, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StorageLimits(Duration.ofHours(1), 1024, 1024).maxFileBytes()).isEqualTo(1024L);
    }

    @Test
    void anArtifactComesOnlyWithADeliveredRunRecordedNow() {
        StoredArtifact artifact = artifact();
        assertThat(new FinishResult(RunOutcomeKind.COMPLETED, Optional.empty(), Optional.of(artifact), true)
                .artifact()).contains(artifact);
        assertThatThrownBy(() -> new FinishResult(RunOutcomeKind.COMPLETED, Optional.empty(), Optional.empty(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinishResult(RunOutcomeKind.REFUSED, Optional.of("x"), Optional.of(artifact),
                true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new FinishResult(RunOutcomeKind.COMPLETED, Optional.empty(), Optional.empty(), false)
                .recordedNow()).isFalse();
    }

    @Test
    void goneIsOnlyExpiredOrPurged() {
        assertThat(new Retrieval.Gone(artifact(), ArtifactState.EXPIRED).state()).isEqualTo(ArtifactState.EXPIRED);
        assertThatThrownBy(() -> new Retrieval.Gone(artifact(), ArtifactState.AVAILABLE))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] bytes = {1, 2};
        assertThat(new Retrieval.Available(artifact(), bytes).content() == bytes).isTrue();
    }

    private static StoredArtifact artifact() {
        return new StoredArtifact(ID, ID, ID, ID, "m", "c", "a".repeat(64), 2, List.of("Reporte", "Metodología"),
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
    }
}
