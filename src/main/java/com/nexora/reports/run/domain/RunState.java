package com.nexora.reports.run.domain;

import java.util.Optional;

/**
 * The state of a run as read: its recorded outcome or, without one, IN_PROGRESS until its deadline and INTERRUPTED
 * after it (even before the reconciliation records it). Derived, never stored (D-B8-3).
 */
public enum RunState {
    IN_PROGRESS, COMPLETED, PARTIAL, REFUSED, FAILED, INTERRUPTED;

    public static RunState of(Optional<RunOutcomeKind> outcome, boolean overdue) {
        if (outcome.isPresent()) {
            return valueOf(outcome.get().name());
        }
        return overdue ? INTERRUPTED : IN_PROGRESS;
    }
}
