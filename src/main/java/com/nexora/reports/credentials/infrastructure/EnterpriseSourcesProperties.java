package com.nexora.reports.credentials.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Read-only enterprise connections and the destination policy (R1 configuration-based provider). Passwords are
 * supplied through environment variables or an external configuration file, never committed.
 *
 * @param allowedHosts exact host names or IP literals that enterprise connections may target
 * @param allowedPorts ports that enterprise connections may target
 * @param sources      one entry per (tenant, data source)
 */
@Validated
@ConfigurationProperties("nexora.reports.enterprise")
public record EnterpriseSourcesProperties(List<String> allowedHosts, List<Integer> allowedPorts,
                                          @Valid List<Source> sources) {

    public EnterpriseSourcesProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        allowedPorts = allowedPorts == null ? List.of() : List.copyOf(allowedPorts);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * @param sslMode       PostgreSQL JDBC sslmode; {@code verify-full} is recommended outside local development and
     *                      required by every {@code /2} report run and tenancy registration (A4-B5)
     * @param maxConcurrent simultaneous executions allowed against this source
     * @param sslRootCert   A4-B9 (B5-O3): optional path of the CA certificate that {@code verify-full} trusts; absent,
     *                      the driver's default location is used (R1 behaviour unchanged)
     * @param transportException EX-TLS-1: temporary authorization to connect to this source without TLS; refused at
     *                      startup unless the build has the capability and every condition holds
     *                      ({@link ConfiguredCredentialProvider}). Absent in every normal configuration
     */
    public record Source(@NotNull UUID tenantId, @NotNull UUID dataSourceId, @NotBlank String host, int port,
                         @NotBlank String database, @NotBlank String username, @NotBlank String password,
                         String sslMode, int maxConcurrent, String sslRootCert,
                         TransportExceptionSettings transportException) {

        @Override
        public String toString() {
            return "Source[tenantId=" + tenantId + ", dataSourceId=" + dataSourceId + ", password=***]";
        }
    }

    /**
     * EX-TLS-1: the authorization as configured ({@code transport-exception.id}, {@code .expires-at} as an ISO-8601
     * instant, {@code .binding}). Validated by {@link ConfiguredCredentialProvider}; the expiry is parsed there so
     * that a malformed value is a clear startup error.
     */
    public record TransportExceptionSettings(String id, String expiresAt, String binding) {
    }
}
