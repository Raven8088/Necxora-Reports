package com.nexora.reports.security;

import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.Requester.PrincipalType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maps a validated JWT (signature, issuer, expiry and audience are checked before) to the Reports requester. The
 * token is rejected ({@code invalid_token}, HTTP 401) when the subject is missing or malformed, the principal type is
 * not exactly HUMAN, SERVICE or AI, or the tenant claim is missing or not a canonical UUID: every execution is
 * tenant-scoped, so there are no tenant-less principals in Reports.
 * <p>
 * Defense in depth: AI principals never receive {@link Permission#EXECUTE}, whatever roles the identity provider
 * assigns, because no delegation and authorization policy for AI executors exists yet; and only persons receive
 * {@link Permission#TENANCY_MANAGE} (A4-B9).
 */
public class RequesterJwtConverter implements Converter<Jwt, JwtAuthenticationToken> {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_SUBJECT_LENGTH = 255;

    private final ReportsSecurityProperties properties;

    public RequesterJwtConverter(ReportsSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH
                || subject.chars().anyMatch(Character::isISOControl)) {
            throw invalidToken();
        }
        PrincipalType type = principalType(claim(jwt.getClaims(), properties.principalTypeClaim()));
        Object tenant = claim(jwt.getClaims(), properties.tenantClaim());
        if (!(tenant instanceof String text) || !CANONICAL_UUID.matcher(text).matches()) {
            throw invalidToken();
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        roles(claim(jwt.getClaims(), properties.rolesClaim()))
                .forEach(name -> Permission.Role.fromName(name).ifPresent(r -> permissions.addAll(r.permissions())));
        if (type == PrincipalType.AI) {
            permissions.clear();
        }
        if (type != PrincipalType.HUMAN) {
            // A4-B9 (D-B9-5): tenancy is managed by persons only, never by a service or an AI principal.
            permissions.remove(Permission.TENANCY_MANAGE);
        }
        Requester requester = new Requester(subject, type, UUID.fromString((String) tenant));
        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(p -> new SimpleGrantedAuthority(p.authority())).toList();
        return new ReportsAuthenticationToken(jwt, requester, authorities);
    }

    private static PrincipalType principalType(Object value) {
        if (value instanceof String text) {
            for (PrincipalType type : PrincipalType.values()) {
                if (type.name().equals(text)) {
                    return type;
                }
            }
        }
        throw invalidToken();
    }

    private static List<String> roles(Object claim) {
        if (claim instanceof Collection<?> names) {
            return names.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return claim instanceof String name ? List.of(name) : List.of();
    }

    /** Reads a claim by name; a dotted name navigates nested objects. */
    static Object claim(Map<String, Object> claims, String name) {
        if (claims.containsKey(name)) {
            return claims.get(name);
        }
        Object current = claims;
        for (String part : name.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static InvalidBearerTokenException invalidToken() {
        return new InvalidBearerTokenException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN).getErrorCode());
    }
}
