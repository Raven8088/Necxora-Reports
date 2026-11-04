package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.application.port.TenantScopes;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.tenancy.application.TenancyRegistrationService;

import java.time.Clock;
import java.util.Objects;

/**
 * A4-B9: the tenant scope check of a run, from the registration active for its (tenant, data source) when the run
 * starts (A4-B5, {@link TenantScopeProbe}). Without an active registration the probe refuses.
 */
public class RegisteredTenantScopes implements TenantScopes {

    private final TenancyRegistrationService registrations;
    private final Clock clock;

    public RegisteredTenantScopes(TenancyRegistrationService registrations) {
        this(registrations, Clock.systemUTC());
    }

    /** @param clock decides whether a transport exception has expired (EX-TLS-1) */
    public RegisteredTenantScopes(TenancyRegistrationService registrations, Clock clock) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TenantScopeVerifier forExecution(ExecutionRequestV2 request) {
        return TenantScopeProbe.forExecution(registrations.active(request.tenantId(), request.dataSourceId()),
                request, clock);
    }
}
