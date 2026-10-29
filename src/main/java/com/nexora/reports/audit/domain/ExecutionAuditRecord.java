package com.nexora.reports.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Technical audit of one execution request. It deliberately has no field for filter values, SQL text, result rows
 * or credentials: only identifiers, classifications, counts and timings.
 *
 * @param planFingerprint opaque fingerprint as returned by Semantic (null if the plan was never read)
 * @param validityStatus  Semantic validity status used (null if never obtained)
 * @param errorCode       stable error code of a refusal or failure (null on success)
 * @param rowCount        rows returned (null unless SUCCEEDED)
 */
public record ExecutionAuditRecord(
        UUID executionId,
        UUID tenantId,
        UUID dataSourceId,
        UUID planId,
        String requesterSubject,
        String requesterType,
        String planFingerprint,
        String validityStatus,
        Outcome outcome,
        String errorCode,
        Integer rowCount,
        boolean truncated,
        long durationMillis,
        Instant startedAt) {

    public enum Outcome { SUCCEEDED, REJECTED, FAILED }

    public ExecutionAuditRecord {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(requesterSubject, "requesterSubject");
        Objects.requireNonNull(requesterType, "requesterType");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
