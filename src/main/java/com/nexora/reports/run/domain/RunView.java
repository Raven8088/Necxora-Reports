package com.nexora.reports.run.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A run as read by its owner: start, derived state, outcome if any, artifact metadata and its state if any. */
public record RunView(UUID runId, UUID tenantId, UUID dataSourceId, UUID reportPlanId, RunOwner owner,
                      Instant startedAt, Instant deadlineAt, RunState state, Optional<RunOutcome> outcome,
                      Optional<Instant> finishedAt, Optional<StoredArtifact> artifact,
                      Optional<ArtifactState> artifactState) {

    public RunView {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(reportPlanId, "reportPlanId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(artifactState, "artifactState");
        if (artifact.isPresent() != artifactState.isPresent()) {
            throw new IllegalArgumentException("an artifact has a state");
        }
    }
}
