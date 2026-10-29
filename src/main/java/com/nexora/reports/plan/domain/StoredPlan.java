package com.nexora.reports.plan.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted plan as returned by {@code GET /api/v1/data-sources/{id}/logical-plans/{planId}} of Nexora Semantic.
 * {@link #fingerprint()} is opaque: Reports compares it for equality only and never recomputes it (it is derived
 * from Semantic's internal Java representation and is not a verifiable signature).
 */
public record StoredPlan(UUID id, UUID dataSourceId, String formatVersion, String fingerprint, String createdBy,
                         Instant createdAt, LogicalPlan plan) {
}
