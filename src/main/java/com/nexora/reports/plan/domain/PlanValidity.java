package com.nexora.reports.plan.domain;

import java.util.List;
import java.util.UUID;

/**
 * Validity of a plan as derived by Semantic ({@code GET …/logical-plans/{planId}/validity}). Issue codes are kept
 * as text: Reports only relies on the {@code blocking} classification made by Semantic and on the closed set of
 * {@link Status} values.
 */
public record PlanValidity(UUID planId, Status status, List<Issue> issues) {

    public enum Status { CURRENT, CURRENT_WITH_CHANGES, STALE }

    public record Issue(String code, Boolean blocking, String kind, UUID referenceId, String message) {
    }

    public PlanValidity {
        issues = issues == null ? null : List.copyOf(issues);
    }
}
