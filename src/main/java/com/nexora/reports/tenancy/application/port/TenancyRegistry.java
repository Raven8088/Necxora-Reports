package com.nexora.reports.tenancy.application.port;

import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyRegistration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only store of tenancy registrations and decisions (D-B5-1, P-1b). Reports owns it during A4; the port lets
 * the authority move to Semantic later without touching the checks. Every read is scoped by tenant.
 */
public interface TenancyRegistry {

    List<TenancyRegistration> registrations(UUID tenantId, UUID dataSourceId);

    List<TenancyDecision> decisions(UUID tenantId, UUID dataSourceId);

    Optional<TenancyRegistration> registration(UUID tenantId, UUID registrationId);

    void insert(TenancyRegistration registration);

    void insert(TenancyDecision decision);
}
