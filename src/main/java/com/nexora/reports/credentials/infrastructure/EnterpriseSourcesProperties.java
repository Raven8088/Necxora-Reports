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
     * @param sslMode       PostgreSQL JDBC sslmode; {@code verify-full} is recommended outside local development
     * @param maxConcurrent simultaneous executions allowed against this source
     */
    public record Source(@NotNull UUID tenantId, @NotNull UUID dataSourceId, @NotBlank String host, int port,
                         @NotBlank String database, @NotBlank String username, @NotBlank String password,
                         String sslMode, int maxConcurrent) {

        @Override
        public String toString() {
            return "Source[tenantId=" + tenantId + ", dataSourceId=" + dataSourceId + ", password=***]";
        }
    }
}
