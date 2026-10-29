package com.nexora.reports.execution.application.port;

import com.nexora.reports.credentials.domain.DataSourceCredentials;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the read-only connection of a data source for a tenant. The pair {@code (tenantId, dataSourceId)} is the
 * only key: a source configured for another tenant is never returned. Implementations must apply the destination
 * policy (allowed hosts and ports) before returning credentials.
 */
public interface CredentialProvider {

    Optional<DataSourceCredentials> resolve(UUID tenantId, UUID dataSourceId);
}
