package com.nexora.reports.run.domain;

/**
 * How a run ended, recorded once (A4-B8). COMPLETED, PARTIAL and REFUSED are the states of {@code nexora-report-run/1};
 * FAILED (an internal error) and INTERRUPTED (no outcome before the deadline, closed by the reconciliation) are
 * internal: how they are exposed is decided in B9.
 */
public enum RunOutcomeKind {
    COMPLETED, PARTIAL, REFUSED, FAILED, INTERRUPTED;

    /** Whether a run that ended so has an artifact: exactly COMPLETED and PARTIAL. */
    public boolean hasArtifact() {
        return this == COMPLETED || this == PARTIAL;
    }
}
