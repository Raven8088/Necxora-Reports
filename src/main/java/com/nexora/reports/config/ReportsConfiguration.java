package com.nexora.reports.config;

import com.nexora.reports.audit.infrastructure.JdbcExecutionAuditRepository;
import com.nexora.reports.credentials.infrastructure.ConfiguredCredentialProvider;
import com.nexora.reports.credentials.infrastructure.EnterpriseSourcesProperties;
import com.nexora.reports.execution.application.ReportExecutionService;
import com.nexora.reports.execution.application.port.AuditPort;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.PlanSource;
import com.nexora.reports.execution.application.port.ReadOnlyExecutor;
import com.nexora.reports.execution.infrastructure.ConnectionPools;
import com.nexora.reports.execution.infrastructure.PostgresReadOnlyExecutor;
import com.nexora.reports.plan.infrastructure.BoundedHttp;
import com.nexora.reports.plan.infrastructure.PlanDocumentReader;
import com.nexora.reports.plan.infrastructure.SemanticPlanSource;
import com.nexora.reports.plan.infrastructure.SemanticProperties;
import com.nexora.reports.plan.infrastructure.ServiceTokenProvider;
import com.nexora.reports.security.ReportsSecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/** Wiring of the hexagon: ports of the application layer bound to their infrastructure adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ExecutionProperties.class, SemanticProperties.class,
        EnterpriseSourcesProperties.class, ReportsSecurityProperties.class})
public class ReportsConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PlanDocumentReader planDocumentReader() {
        return new PlanDocumentReader();
    }

    /**
     * Decoder for the service tokens Reports obtains for itself: same issuer as user tokens (signature, issuer and
     * expiry validated), resolved lazily so that startup does not depend on the identity provider being reachable.
     */
    @Bean
    ServiceTokenProvider serviceTokenProvider(SemanticProperties semantic, ReportsSecurityProperties security,
                                              OAuth2ResourceServerProperties resourceServer, Clock clock) {
        String issuer = resourceServer.getJwt().getIssuerUri();
        Supplier<JwtDecoder> decoder = new Supplier<>() {
            private volatile JwtDecoder instance;

            @Override
            public JwtDecoder get() {
                JwtDecoder current = instance;
                if (current == null) {
                    synchronized (this) {
                        current = instance;
                        if (current == null) {
                            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withIssuerLocation(issuer).build();
                            nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                                    JwtValidators.createDefaultWithIssuer(issuer)));
                            instance = current = nimbus;
                        }
                    }
                }
                return current;
            }
        };
        BoundedHttp http = new BoundedHttp(semantic.connectTimeout(), semantic.readTimeout(),
                semantic.maxResponseBytes());
        return new ServiceTokenProvider(semantic, http, decoder, security.tenantClaim(),
                security.principalTypeClaim(), security.rolesClaim(), clock);
    }

    @Bean
    PlanSource planSource(SemanticProperties semantic, ServiceTokenProvider tokens, PlanDocumentReader reader) {
        String scheme = semantic.baseUrl().getScheme();
        if (!("https".equals(scheme) || "http".equals(scheme)) || semantic.baseUrl().getUserInfo() != null) {
            throw new IllegalStateException("nexora.reports.semantic.base-url must be an http(s) URL without user info");
        }
        BoundedHttp http = new BoundedHttp(semantic.connectTimeout(), semantic.readTimeout(),
                semantic.maxResponseBytes());
        return new SemanticPlanSource(semantic.baseUrl(), http, tokens, reader);
    }

    @Bean
    CredentialProvider credentialProvider(EnterpriseSourcesProperties properties) {
        return new ConfiguredCredentialProvider(properties);
    }

    @Bean(destroyMethod = "close")
    ConnectionPools connectionPools(ExecutionProperties execution) {
        return new ConnectionPools(execution.limits());
    }

    @Bean
    ReadOnlyExecutor readOnlyExecutor(ConnectionPools pools, ExecutionProperties execution) {
        return new PostgresReadOnlyExecutor(pools, execution.concurrency());
    }

    @Bean
    JdbcExecutionAuditRepository auditRepository(JdbcClient jdbc) {
        return new JdbcExecutionAuditRepository(jdbc);
    }

    @Bean
    ReportExecutionService reportExecutionService(PlanSource planSource, CredentialProvider credentials,
                                                  ReadOnlyExecutor executor, AuditPort audit,
                                                  ExecutionProperties execution, Clock clock) {
        return new ReportExecutionService(planSource, credentials, executor, audit, execution.limits(), clock,
                UUID::randomUUID);
    }
}
