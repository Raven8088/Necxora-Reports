package com.nexora.reports.tenancy;

import com.nexora.reports.tenancy.application.port.TenancyRegistry;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test-only, append-only registry with the same rules as the database (one version number per source, one decision
 * of each kind per version, no approval by the registrant).
 */
public final class InMemoryTenancyRegistry implements TenancyRegistry {

    private final List<TenancyRegistration> registrations = new ArrayList<>();
    private final List<TenancyDecision> decisions = new ArrayList<>();

    @Override
    public synchronized List<TenancyRegistration> registrations(UUID tenantId, UUID dataSourceId) {
        return registrations.stream().filter(r -> r.tenantId().equals(tenantId)
                && r.dataSourceId().equals(dataSourceId)).toList();
    }

    @Override
    public synchronized List<TenancyDecision> decisions(UUID tenantId, UUID dataSourceId) {
        List<UUID> ids = registrations(tenantId, dataSourceId).stream().map(TenancyRegistration::id).toList();
        return decisions.stream().filter(d -> ids.contains(d.registrationId())).toList();
    }

    @Override
    public synchronized Optional<TenancyRegistration> registration(UUID tenantId, UUID registrationId) {
        return registrations.stream().filter(r -> r.tenantId().equals(tenantId) && r.id().equals(registrationId))
                .findFirst();
    }

    @Override
    public synchronized void insert(TenancyRegistration registration) {
        if (registrations.stream().anyMatch(r -> r.tenantId().equals(registration.tenantId())
                && r.dataSourceId().equals(registration.dataSourceId()) && r.version() == registration.version())) {
            throw new IllegalStateException("duplicate version");
        }
        registrations.add(registration);
    }

    @Override
    public synchronized void insert(TenancyDecision decision) {
        TenancyRegistration target = registrations.stream().filter(r -> r.id().equals(decision.registrationId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("unknown registration"));
        if (decisions.stream().anyMatch(d -> d.registrationId().equals(decision.registrationId())
                && d.kind() == decision.kind())) {
            throw new IllegalStateException("duplicate decision");
        }
        if (decision.kind() == TenancyDecision.Kind.APPROVED && target.registeredBy().equals(decision.decidedBy())) {
            throw new IllegalStateException("the registrant cannot approve");
        }
        decisions.add(decision);
    }
}
