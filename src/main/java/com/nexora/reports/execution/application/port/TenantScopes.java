package com.nexora.reports.execution.application.port;

import com.nexora.reports.execution.domain.ExecutionRequestV2;

/**
 * A4-B9: the mandatory tenant scope check of one {@code /2} execution (A4-B5), built from the registration that is
 * active for the request's tenant and data source when the run starts. Without an active registration the check
 * refuses every execution ({@code TENANT_SCOPE_UNVERIFIED}); there is no permissive alternative.
 */
public interface TenantScopes {

    TenantScopeVerifier forExecution(ExecutionRequestV2 request);
}
