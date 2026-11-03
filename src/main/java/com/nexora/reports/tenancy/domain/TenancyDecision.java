package com.nexora.reports.tenancy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An approval or a revocation of one registration version, by one person. Decisions are append-only. */
public record TenancyDecision(UUID id, UUID registrationId, Kind kind, String decidedBy, Instant decidedAt) {

    public enum Kind { APPROVED, REVOKED }

    public TenancyDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(registrationId, "registrationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
