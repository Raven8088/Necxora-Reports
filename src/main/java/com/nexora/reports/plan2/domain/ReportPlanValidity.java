package com.nexora.reports.plan2.domain;

import java.util.List;
import java.util.Objects;

/**
 * Whether a report plan still describes the catalog, as Nexora Semantic answers it ({@code GET
 * .../report-plans/{id}/validity}, A3). There is no intermediate state: a STALE plan is never executed (A4-B9, steps
 * 6 and 9). {@code issueCodes} are Semantic's codes (for example {@code OBJECT_CHANGED}), for the logs only.
 */
public record ReportPlanValidity(Status status, List<String> issueCodes) {

    public enum Status { CURRENT, STALE }

    public ReportPlanValidity {
        Objects.requireNonNull(status, "status");
        issueCodes = List.copyOf(issueCodes);
    }

    public boolean current() {
        return status == Status.CURRENT;
    }
}
