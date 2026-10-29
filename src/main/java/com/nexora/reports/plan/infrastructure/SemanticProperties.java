package com.nexora.reports.plan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Connection of Reports to Nexora Semantic.
 *
 * @param baseUrl          Semantic base URL (operator configuration; never taken from a request)
 * @param tokenUri         OAuth2 token endpoint used for the client-credentials grant of the service accounts
 * @param expectedAudience audience a service token must carry to be sent to Semantic
 * @param connectTimeout   connection timeout of every call
 * @param readTimeout      response timeout of every call
 * @param maxResponseBytes upper bound of a Semantic or token response body
 * @param serviceAccounts  one REPORTS_CLIENT service account per tenant (its token carries that tenant)
 */
@Validated
@ConfigurationProperties("nexora.reports.semantic")
public record SemanticProperties(
        @NotNull URI baseUrl,
        @NotNull URI tokenUri,
        @NotBlank String expectedAudience,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        int maxResponseBytes,
        @Valid List<ServiceAccount> serviceAccounts) {

    /** Secrets come from the environment or a secret store, never from the repository. */
    public record ServiceAccount(@NotNull UUID tenantId, @NotBlank String clientId, @NotBlank String clientSecret) {
        @Override
        public String toString() {
            return "ServiceAccount[tenantId=" + tenantId + ", clientId=" + clientId + ", clientSecret=***]";
        }
    }

    public SemanticProperties {
        serviceAccounts = serviceAccounts == null ? List.of() : List.copyOf(serviceAccounts);
        if (maxResponseBytes <= 0) {
            maxResponseBytes = 4 * 1024 * 1024;
        }
    }
}
