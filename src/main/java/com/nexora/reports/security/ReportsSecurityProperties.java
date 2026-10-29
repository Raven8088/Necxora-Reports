package com.nexora.reports.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Names of the access-token claims Reports reads (same conventions as Nexora Semantic). A dotted name reaches a
 * nested claim, e.g. {@code realm_access.roles}.
 */
@Validated
@ConfigurationProperties("nexora.reports.security")
public record ReportsSecurityProperties(@NotBlank String tenantClaim, @NotBlank String principalTypeClaim,
                                        @NotBlank String rolesClaim) {
}
