package com.nexora.reports.execution.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who asked for an execution, taken exclusively from a validated access token. {@code tenantId} is the only tenant
 * Reports ever acts for; nothing in a path or a body can change it.
 */
public record Requester(String subject, PrincipalType type, UUID tenantId) {

    /** Same principal types as Nexora Semantic (decision D2). */
    public enum PrincipalType { HUMAN, SERVICE, AI }

    public Requester {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tenantId, "tenantId");
    }
}
