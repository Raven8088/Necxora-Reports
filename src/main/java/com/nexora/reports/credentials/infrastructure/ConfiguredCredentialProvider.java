package com.nexora.reports.credentials.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.SourceTarget;
import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.credentials.domain.TransportPolicy;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.SourceAvailability;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.plan2.infrastructure.JdkSha256;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final Sha256 SHA256 = new JdkSha256();
    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredCredentialProvider.class);

    private static final Set<String> SSL_MODES = Set.of("disable", "allow", "prefer", "require", "verify-ca",
            "verify-full");

    private record Key(UUID tenantId, UUID dataSourceId) {
    }

    private final Map<Key, DataSourceCredentials> sources = new HashMap<>();
    private final TargetPolicy policy;

    /** A provider of a normal build: no transport exception can be configured (EX-TLS-1, factor F1). */
    public ConfiguredCredentialProvider(EnterpriseSourcesProperties properties) {
        this(properties, TransportCapability.NONE, Set.of(), Clock.systemUTC());
    }

    /**
     * @param capability     whether this build may accept a transport exception (EX-TLS-1, F1)
     * @param activeProfiles the active Spring profiles (F2, and the refusal of {@code prod})
     * @param clock          for the expiry checks at startup (F4)
     */
    public ConfiguredCredentialProvider(EnterpriseSourcesProperties properties, TransportCapability capability,
                                        Set<String> activeProfiles, Clock clock) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(activeProfiles, "activeProfiles");
        Objects.requireNonNull(clock, "clock");
        if (capability.capable() && activeProfiles.contains("prod")) {
            throw new IllegalStateException("a build with the insecure-transport capability never runs with the prod"
                    + " profile");
        }
        if (capability.capable()) {
            LOG.warn("EX-TLS-1: this build has the INSECURE TRANSPORT capability (built {}); it must never be used"
                    + " in production", capability.builtAt());
        }
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
            TransportException exception = s.transportException() == null ? null
                    : transportException(s, sslMode, capability, activeProfiles, clock.instant());
            DataSourceCredentials credentials = new DataSourceCredentials(s.tenantId(), s.dataSourceId(), s.host(),
                    s.port(), s.database(), s.username(), s.password(), sslMode,
                    s.maxConcurrent() < 1 ? 4 : s.maxConcurrent(),
                    s.sslRootCert() == null || s.sslRootCert().isBlank() ? null : s.sslRootCert(), exception);
            if (sources.putIfAbsent(new Key(s.tenantId(), s.dataSourceId()), credentials) != null) {
                throw new IllegalStateException("data source " + s.dataSourceId() + " configured twice");
            }
        }
        if (sources.keySet().stream().map(Key::dataSourceId).distinct().count() != sources.size()) {
            throw new IllegalStateException("a data source id is configured for more than one tenant");
        }
    }

    /**
     * EX-TLS-1: validates the transport exception of one source; any doubt stops the application (fail closed).
     * Factors: F1 build capability, F2 Spring profile, F5 {@code sslmode=disable}; destination: a private IPv4
     * literal (never a name, which could resolve elsewhere without TLS to notice) inside the allowed destinations;
     * F4 expiry in the future and at most {@link TransportPolicy#MAX_VALIDITY} after the build; and the binding of
     * the whole destination.
     */
    private static TransportException transportException(EnterpriseSourcesProperties.Source s, String sslMode,
                                                         TransportCapability capability, Set<String> profiles,
                                                         Instant now) {
        String source = "data source " + s.dataSourceId() + ": ";
        EnterpriseSourcesProperties.TransportExceptionSettings t = s.transportException();
        if (t.id() == null && t.expiresAt() == null && t.binding() == null) {
            throw new IllegalStateException(source + "an empty transport exception is not allowed");
        }
        if (!capability.capable()) {
            throw new IllegalStateException(source + "a transport exception is configured but this build has no"
                    + " insecure-transport capability (Maven profile dev-insecure-transport)");
        }
        if (!profiles.contains(TransportPolicy.DEV_PROFILE)) {
            throw new IllegalStateException(source + "a transport exception requires the Spring profile "
                    + TransportPolicy.DEV_PROFILE);
        }
        if (!"disable".equals(sslMode)) {
            throw new IllegalStateException(source + "a transport exception requires sslMode=disable");
        }
        if (!isPrivateIpv4Literal(s.host())) {
            throw new IllegalStateException(source + "a transport exception requires a private IPv4 literal host");
        }
        if (t.id() == null || !TransportException.ID.matcher(t.id()).matches()) {
            throw new IllegalStateException(source + "invalid transport exception id");
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(t.expiresAt() == null ? "" : t.expiresAt());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(source + "transport exception expires-at must be an ISO-8601 instant");
        }
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException(source + "the transport exception has expired");
        }
        if (expiresAt.isAfter(capability.builtAt().plus(TransportPolicy.MAX_VALIDITY))) {
            throw new IllegalStateException(source + "the transport exception may last at most "
                    + TransportPolicy.MAX_VALIDITY.toDays() + " days after the build");
        }
        String expected = TransportException.binding(s.tenantId(), s.dataSourceId(), s.host(), s.port(),
                s.database(), s.username(), sslMode, t.id(), expiresAt, SHA256);
        if (!expected.equals(t.binding())) {
            throw new IllegalStateException(source + "the transport exception binding does not match the configured"
                    + " destination");
        }
        LOG.warn("EX-TLS-1: data source {} uses transport exception {} until {}: the connection is NOT encrypted and"
                + " the server identity is NOT verified", s.dataSourceId(), t.id(), expiresAt);
        return new TransportException(t.id(), expiresAt, expected);
    }

    /** {@code 10/8}, {@code 172.16/12} or {@code 192.168/16}, written as a canonical dotted IPv4 literal. */
    static boolean isPrivateIpv4Literal(String host) {
        if (host == null || !host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) {
            return false;
        }
        int[] o = Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(o).anyMatch(v -> v > 255)) {
            return false;
        }
        return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168);
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
