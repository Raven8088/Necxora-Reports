package com.nexora.reports.plan.infrastructure;

import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.infrastructure.SemanticProperties.ServiceAccount;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Obtains the access token of the REPORTS_CLIENT service account of a tenant (OAuth2 client credentials) and checks
 * it before use: valid signature, issuer and expiry (decoder), the Semantic audience, principal type SERVICE, the
 * REPORTS_CLIENT role and, above all, a tenant claim equal to the tenant Reports is acting for. A token is cached per
 * tenant and is never used for any other tenant.
 */
public class ServiceTokenProvider {

    static final String REPORTS_CLIENT_ROLE = "REPORTS_CLIENT";
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private record CachedToken(String value, Instant refreshAt) {
    }

    private final Map<UUID, ServiceAccount> accounts = new HashMap<>();
    private final Map<UUID, CachedToken> cache = new ConcurrentHashMap<>();
    private final BoundedHttp http;
    private final SemanticProperties properties;
    private final Supplier<JwtDecoder> decoder;
    private final String tenantClaim;
    private final String principalTypeClaim;
    private final String rolesClaim;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public ServiceTokenProvider(SemanticProperties properties, BoundedHttp http, Supplier<JwtDecoder> decoder,
                                String tenantClaim, String principalTypeClaim, String rolesClaim, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.http = Objects.requireNonNull(http);
        this.decoder = Objects.requireNonNull(decoder);
        this.tenantClaim = Objects.requireNonNull(tenantClaim);
        this.principalTypeClaim = Objects.requireNonNull(principalTypeClaim);
        this.rolesClaim = Objects.requireNonNull(rolesClaim);
        this.clock = Objects.requireNonNull(clock);
        for (ServiceAccount account : properties.serviceAccounts()) {
            if (accounts.putIfAbsent(account.tenantId(), account) != null) {
                throw new IllegalStateException("two service accounts configured for one tenant");
            }
        }
    }

    public String tokenFor(UUID tenantId) {
        CachedToken cached = cache.get(tenantId);
        if (cached != null && clock.instant().isBefore(cached.refreshAt())) {
            return cached.value();
        }
        ServiceAccount account = accounts.get(tenantId);
        if (account == null) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "no service account for the tenant");
        }
        String token = request(account);
        Jwt jwt = verify(token, tenantId);
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt != null) {
            cache.put(tenantId, new CachedToken(token, expiresAt.minus(REFRESH_MARGIN)));
        }
        return token;
    }

    /** Forgets the cached token of a tenant (after Semantic rejected it). */
    public void evict(UUID tenantId) {
        cache.remove(tenantId);
    }

    private String request(ServiceAccount account) {
        String basic = "Basic " + Base64.getEncoder().encodeToString((url(account.clientId()) + ":"
                + url(account.clientSecret())).getBytes(StandardCharsets.UTF_8));
        BoundedHttp.Response response;
        try {
            response = http.postForm(properties.tokenUri(), basic, "grant_type=client_credentials");
        } catch (BoundedHttp.TransportException e) {
            throw new ExecutionException(ErrorCode.SEMANTIC_UNAVAILABLE, "token endpoint unreachable", e);
        }
        if (response.status() != 200) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID,
                    "token endpoint answered " + response.status());
        }
        try {
            JsonNode node = json.readTree(response.body());
            JsonNode token = node == null ? null : node.get("access_token");
            if (token == null || !token.isString() || token.asString().isBlank()) {
                throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "no access token in response");
            }
            return token.asString();
        } catch (JacksonException e) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "unreadable token response");
        }
    }

    private Jwt verify(String token, UUID tenantId) {
        Jwt jwt;
        try {
            jwt = decoder.get().decode(token);
        } catch (JwtException e) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "service token rejected by decoder");
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(properties.expectedAudience())) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "service token audience");
        }
        if (!"SERVICE".equals(jwt.getClaims().get(principalTypeClaim))) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "service token principal type");
        }
        Object roles = jwt.getClaims().get(rolesClaim);
        boolean reportsClient = roles instanceof Collection<?> list
                ? list.stream().anyMatch(r -> REPORTS_CLIENT_ROLE.equals(r))
                : REPORTS_CLIENT_ROLE.equals(roles);
        if (!reportsClient) {
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "service token without REPORTS_CLIENT");
        }
        if (!tenantId.toString().equals(jwt.getClaims().get(tenantClaim))) {
            // Never send a token of another tenant (or without tenant) to Semantic.
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "service token tenant mismatch");
        }
        return jwt;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
