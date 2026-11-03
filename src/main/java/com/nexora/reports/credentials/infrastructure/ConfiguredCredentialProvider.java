package com.nexora.reports.credentials.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.SourceTarget;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.SourceAvailability;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * R1 credential provider backed by configuration ({@link EnterpriseSourcesProperties}). Lookups are keyed by the
 * pair (tenant, data source), so a source configured for another tenant is invisible. The destination policy is
 * applied at startup (fail fast) and again at every resolution. It is meant to be replaced by a secret-manager
 * implementation of {@link CredentialProvider} without touching the rest of the service.
 * <p>
 * B10-O3: it also answers {@link SourceAvailability} (the destination without its secret). Here every secret is
 * read from the configuration at startup and kept in memory, and neither the sources nor the policy change while
 * the application runs; a secret-manager implementation would resolve the secret only in {@link #resolve}.
 */
public class ConfiguredCredentialProvider implements CredentialProvider, SourceAvailability {

    private static final Set<String> SSL_MODES = Set.of("disable", "allow", "prefer", "require", "verify-ca",
            "verify-full");

    private record Key(UUID tenantId, UUID dataSourceId) {
    }

    private final Map<Key, DataSourceCredentials> sources = new HashMap<>();
    private final TargetPolicy policy;

    public ConfiguredCredentialProvider(EnterpriseSourcesProperties properties) {
        this.policy = new TargetPolicy(properties.allowedHosts(), properties.allowedPorts());
        List<EnterpriseSourcesProperties.Source> configured = properties.sources();
        for (EnterpriseSourcesProperties.Source s : configured) {
            String sslMode = s.sslMode() == null || s.sslMode().isBlank() ? "verify-full" : s.sslMode();
            if (!SSL_MODES.contains(sslMode)) {
                throw new IllegalStateException("invalid sslMode for data source " + s.dataSourceId());
            }
            if (!policy.allows(s.host(), s.port())) {
                throw new IllegalStateException("data source " + s.dataSourceId()
                        + " targets a host or port outside the allowed destinations");
            }
            DataSourceCredentials credentials = new DataSourceCredentials(s.tenantId(), s.dataSourceId(), s.host(),
                    s.port(), s.database(), s.username(), s.password(), sslMode,
                    s.maxConcurrent() < 1 ? 4 : s.maxConcurrent(),
                    s.sslRootCert() == null || s.sslRootCert().isBlank() ? null : s.sslRootCert());
            if (sources.putIfAbsent(new Key(s.tenantId(), s.dataSourceId()), credentials) != null) {
                throw new IllegalStateException("data source " + s.dataSourceId() + " configured twice");
            }
        }
        if (sources.keySet().stream().map(Key::dataSourceId).distinct().count() != sources.size()) {
            throw new IllegalStateException("a data source id is configured for more than one tenant");
        }
    }

    @Override
    public Optional<DataSourceCredentials> resolve(UUID tenantId, UUID dataSourceId) {
        return allowed(tenantId, dataSourceId);
    }

    @Override
    public Optional<SourceTarget> configured(UUID tenantId, UUID dataSourceId) {
        return allowed(tenantId, dataSourceId).map(DataSourceCredentials::target);
    }

    /** The same lookup and the same destination policy for both ports. */
    private Optional<DataSourceCredentials> allowed(UUID tenantId, UUID dataSourceId) {
        DataSourceCredentials credentials = sources.get(new Key(tenantId, dataSourceId));
        if (credentials == null) {
            return Optional.empty();
        }
        if (!policy.allows(credentials.host(), credentials.port())) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED, "destination not allowed");
        }
        return Optional.of(credentials);
    }
}
