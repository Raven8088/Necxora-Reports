package com.nexora.reports.config;

import com.nexora.reports.api.CorrelationIdFilter;
import com.nexora.reports.api.Problems;
import com.nexora.reports.security.ReportsSecurityProperties;
import com.nexora.reports.security.RequesterArgumentResolver;
import com.nexora.reports.security.RequesterJwtConverter;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Stateless OAuth2 resource server (any OIDC provider; Keycloak in development). The issuer is mandatory and always
 * validated, the audience is configured with {@code spring.security.oauth2.resourceserver.jwt.audiences}, and
 * authorization is permission-based ({@code @PreAuthorize}). Everything outside {@code /api/**} and the health probes
 * is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RequesterJwtConverter converter,
                                            AuthenticationEntryPoint entryPoint,
                                            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** Spring Boot adds this validator to the decoder it builds: the issuer is enforced or the app does not start. */
    @Bean
    OAuth2TokenValidator<Jwt> mandatoryIssuerValidator(OAuth2ResourceServerProperties properties) {
        String issuer = properties.getJwt().getIssuerUri();
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri is required: tokens must have a validated issuer");
        }
        return new JwtIssuerValidator(issuer);
    }

    @Bean
    RequesterJwtConverter requesterJwtConverter(ReportsSecurityProperties properties) {
        return new RequesterJwtConverter(properties);
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper json) {
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, exception instanceof InvalidBearerTokenException
                    || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                    ? "Bearer error=\"invalid_token\"" : "Bearer");
            Problems.write(response, HttpStatus.UNAUTHORIZED, Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "A valid access token is required.", request), json);
        };
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper json) {
        return (request, response, exception) -> Problems.write(response, HttpStatus.FORBIDDEN,
                Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Insufficient permissions.", request), json);
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    WebMvcConfigurer requesterArgumentResolverConfigurer() {
        HandlerMethodArgumentResolver resolver = new RequesterArgumentResolver();
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(resolver);
            }
        };
    }
}
