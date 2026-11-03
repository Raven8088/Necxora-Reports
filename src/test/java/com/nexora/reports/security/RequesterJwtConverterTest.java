package com.nexora.reports.security;

import com.nexora.reports.execution.domain.Requester;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequesterJwtConverterTest {

    private final RequesterJwtConverter converter = new RequesterJwtConverter(
            new ReportsSecurityProperties("tenant", "principal_type", "roles"));
    private final String tenant = UUID.randomUUID().toString();

    @Test
    void theTenantComesFromTheTokenAndRolesGrantPermissions() {
        var token = (ReportsAuthenticationToken) converter.convert(jwt(Map.of("sub", "ana", "tenant", tenant,
                "principal_type", "HUMAN", "roles", List.of("REPORT_RUNNER", "VIEWER", "offline_access"))));
        Requester requester = token.requester();
        assertThat(requester.tenantId()).isEqualTo(UUID.fromString(tenant));
        assertThat(requester.type()).isEqualTo(Requester.PrincipalType.HUMAN);
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("reports:execute");
    }

    @Test
    void tenantSubjectAndPermissionsComeOnlyFromTheConfiguredClaims() {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("sub", "ana", "tenant", tenant,
                "principal_type", "HUMAN", "roles", List.of("REPORT_RUNNER")));
        // Claims a consumer could try to inject to switch tenant, re-scope or elevate itself: never read.
        claims.put("tenant_id", UUID.randomUUID().toString());
        claims.put("company_id", "1042");
        claims.put("companyId", "1042");
        claims.put("integration", "wellnode");
        claims.put("actor", Map.of("directory", "x", "id", "y"));
        claims.put("permissions", List.of("platform:tenant:manage"));
        claims.put("authorities", List.of("platform:tenant:manage"));
        var token = (ReportsAuthenticationToken) converter.convert(jwt(claims));

        Requester requester = token.requester();
        assertThat(requester.tenantId()).isEqualTo(UUID.fromString(tenant));
        assertThat(requester.subject()).isEqualTo("ana");
        assertThat(requester.type()).isEqualTo(Requester.PrincipalType.HUMAN);
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("reports:execute");
    }

    @Test
    void aiPrincipalsNeverGetTheExecutePermission() {
        var token = converter.convert(jwt(Map.of("sub", "agent", "tenant", tenant, "principal_type", "AI",
                "roles", List.of("REPORT_RUNNER"))));
        assertThat(token.getAuthorities()).isEmpty();
    }

    /** A4-B9 (D-B9-5): managing tenancy is for persons only, whatever roles a service or an AI is given. */
    @Test
    void onlyPersonsGetTheTenancyPermission() {
        var person = converter.convert(jwt(Map.of("sub", "steward", "tenant", tenant, "principal_type", "HUMAN",
                "roles", List.of("REPORTS_TENANCY_ADMIN", "REPORT_RUNNER"))));
        var service = converter.convert(jwt(Map.of("sub", "svc", "tenant", tenant, "principal_type", "SERVICE",
                "roles", List.of("REPORTS_TENANCY_ADMIN", "REPORT_RUNNER"))));
        var ai = converter.convert(jwt(Map.of("sub", "agent", "tenant", tenant, "principal_type", "AI",
                "roles", List.of("REPORTS_TENANCY_ADMIN"))));

        assertThat(person.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("reports:execute", "reports:tenancy:manage");
        assertThat(service.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("reports:execute");
        assertThat(ai.getAuthorities()).isEmpty();
    }

    @Test
    void theTenancyRoleAloneDoesNotGrantExecution() {
        var token = converter.convert(jwt(Map.of("sub", "steward", "tenant", tenant, "principal_type", "HUMAN",
                "roles", List.of("REPORTS_TENANCY_ADMIN"))));
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("reports:tenancy:manage");
    }

    @Test
    void semanticRolesDoNotGrantReportsPermissions() {
        var token = converter.convert(jwt(Map.of("sub", "svc", "tenant", tenant, "principal_type", "SERVICE",
                "roles", List.of("REPORTS_CLIENT"))));
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void tokensWithoutAValidTenantSubjectOrTypeAreInvalid() {
        assertInvalid(Map.of("sub", "ana", "principal_type", "HUMAN", "roles", List.of("REPORT_RUNNER")));
        assertInvalid(Map.of("sub", "ana", "tenant", "demo", "principal_type", "HUMAN"));
        assertInvalid(Map.of("sub", "ana", "tenant", tenant, "principal_type", "human"));
        assertInvalid(Map.of("sub", "ana", "tenant", tenant));
        assertInvalid(Map.of("sub", "an\na", "tenant", tenant, "principal_type", "HUMAN"));
    }

    @Test
    void nestedClaimsCanBeConfigured() {
        var nested = new RequesterJwtConverter(new ReportsSecurityProperties("tenant", "principal_type",
                "realm_access.roles"));
        var token = nested.convert(jwt(Map.of("sub", "ana", "tenant", tenant, "principal_type", "HUMAN",
                "realm_access", Map.of("roles", List.of("REPORT_RUNNER")))));
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("reports:execute");
    }

    private void assertInvalid(Map<String, Object> claims) {
        assertThatThrownBy(() -> converter.convert(jwt(claims))).isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
