package com.nexora.reports.security;

import com.nexora.reports.execution.domain.Requester;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/** JWT authentication enriched with the Reports requester (subject, type, tenant). */
public class ReportsAuthenticationToken extends JwtAuthenticationToken {

    private final transient Requester requester;

    public ReportsAuthenticationToken(Jwt jwt, Requester requester,
                                      Collection<? extends GrantedAuthority> authorities) {
        super(jwt, authorities, requester.subject());
        this.requester = requester;
    }

    public Requester requester() {
        return requester;
    }
}
