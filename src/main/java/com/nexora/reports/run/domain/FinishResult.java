package com.nexora.reports.run.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What finishing a run recorded. {@code recordedNow} is false when the run already had an outcome (for example the
 * reconciliation closed it as INTERRUPTED): {@code kind} is then that outcome and nothing new was stored.
 */
public record FinishResult(RunOutcomeKind kind, Optional<String> code, Optional<StoredArtifact> artifact,
                           boolean recordedNow) {

    public FinishResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(artifact, "artifact");
        if (artifact.isPresent() != (recordedNow && kind.hasArtifact())) {
            throw new IllegalArgumentException("an artifact comes only with a delivered run recorded now");
        }
    }
}
