package com.nexora.reports.tenancy.domain;

/** Status of a registration version, always derived from the versions and decisions, never stored. */
public enum TenancyStatus {
    /** Registered, waiting for the approval of another person. */
    PENDING,
    /** Approved and the newest approved version not revoked: the one executions are checked against. */
    ACTIVE,
    /** Approved, but a newer version was approved afterwards. */
    SUPERSEDED,
    /** Revoked: never used again. */
    REVOKED
}
