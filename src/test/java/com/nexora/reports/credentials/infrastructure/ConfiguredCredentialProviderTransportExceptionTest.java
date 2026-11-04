package com.nexora.reports.credentials.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.credentials.domain.TransportPolicy;
import com.nexora.reports.plan2.infrastructure.JdkSha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-TLS-1 at startup: every factor (F1 build capability, F2 profile, F4 expiry, F5 mode), the destination and the
 * binding must hold, or the application does not start. Normal builds keep A4-B5 unchanged.
 */
class ConfiguredCredentialProviderTransportExceptionTest {

    private static final UUID TENANT = UUID.fromString("e7100000-0000-4000-8000-000000000011");
    private static final UUID SOURCE = UUID.fromString("e7100000-0000-4000-8000-000000000012");
    private static final String HOST = "172.31.54.3";
    private static final int PORT = 9999;
    private static final Instant BUILT = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-11-17T23:59:59Z");
    private static final TransportCapability CAPABLE = new TransportCapability(true, BUILT);
    private static final Set<String> DEV = Set.of(TransportPolicy.DEV_PROFILE);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static String binding(String host, int port, String database, String user, String mode, String id,
                                  Instant expires) {
        return TransportException.binding(TENANT, SOURCE, host, port, database, user, mode, id, expires,
                new JdkSha256());
    }

    private static EnterpriseSourcesProperties properties(String host, String mode,
                                                          EnterpriseSourcesProperties.TransportExceptionSettings t) {
        return new EnterpriseSourcesProperties(List.of(host), List.of(PORT), List.of(
                new EnterpriseSourcesProperties.Source(TENANT, SOURCE, host, PORT, "witsml", "reader", "s3cret",
                        mode, 2, "/certs/root.crt", t)));
    }

    private static EnterpriseSourcesProperties.TransportExceptionSettings valid() {
        return new EnterpriseSourcesProperties.TransportExceptionSettings("EX-TLS-1", EXPIRES.toString(),
                binding(HOST, PORT, "witsml", "reader", "disable", "EX-TLS-1", EXPIRES));
    }

    private static ConfiguredCredentialProvider provider(EnterpriseSourcesProperties p, TransportCapability c,
                                                         Set<String> profiles) {
        return new ConfiguredCredentialProvider(p, c, profiles, CLOCK);
    }

    @Test
    void aCompleteAuthorizationGivesPlaintextCredentialsCarryingTheException() {
        DataSourceCredentials c = provider(properties(HOST, "disable", valid()), CAPABLE, DEV)
                .resolve(TENANT, SOURCE).orElseThrow();

        assertThat(c.sslMode()).isEqualTo("disable");
        assertThat(c.transportException()).isEqualTo(new TransportException("EX-TLS-1", EXPIRES,
                valid().binding()));
        assertThat(c.toString()).doesNotContain("s3cret");
        assertThat(c.target().toString()).doesNotContain("s3cret");
    }

    @Test
    void f1ANormalBuildRefusesAnyTransportExceptionWhateverTheProfile() {
        assertThatThrownBy(() -> provider(properties(HOST, "disable", valid()), TransportCapability.NONE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("capability");
        assertThatThrownBy(() -> new ConfiguredCredentialProvider(properties(HOST, "disable", valid())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("capability");
    }

    @Test
    void f1ABuildWithTheCapabilityNeverStartsWithTheProdProfileEvenWithoutException() {
        assertThatThrownBy(() -> provider(properties(HOST, "verify-full", null), CAPABLE,
                Set.of("prod", TransportPolicy.DEV_PROFILE))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        assertThatThrownBy(() -> provider(properties(HOST, "verify-full", null), CAPABLE, Set.of("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void f2TheCapabilityWithoutTheProfileRefusesTheException() {
        assertThatThrownBy(() -> provider(properties(HOST, "disable", valid()), CAPABLE, Set.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(TransportPolicy.DEV_PROFILE);
        assertThatThrownBy(() -> provider(properties(HOST, "disable", valid()), CAPABLE, Set.of("default")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow", "prefer", "require", "verify-ca", "verify-full"})
    void f5TheExceptionRequiresSslModeDisable(String mode) {
        assertThatThrownBy(() -> provider(properties(HOST, mode, valid()), CAPABLE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sslMode=disable");
    }

    @Test
    void withoutExceptionDisableStillLoadsAndIsRefusedLaterByTheTransportPolicy() {
        DataSourceCredentials c = provider(properties(HOST, "disable", null), CAPABLE, DEV).resolve(TENANT, SOURCE)
                .orElseThrow();
        assertThat(c.transportException()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"db.example.com", "8.8.8.8", "172.32.0.1", "192.169.1.1", "11.0.0.1", "127.0.0.1",
            "010.1.2.3", "10.1.2", "10.1.2.300", "::1"})
    void theDestinationMustBeAPrivateIpv4Literal(String host) {
        EnterpriseSourcesProperties.TransportExceptionSettings t =
                new EnterpriseSourcesProperties.TransportExceptionSettings("EX-TLS-1", EXPIRES.toString(),
                        binding(host, PORT, "witsml", "reader", "disable", "EX-TLS-1", EXPIRES));
        assertThatThrownBy(() -> provider(properties(host, "disable", t), CAPABLE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("private IPv4");
    }

    @Test
    void privateRangesAreAccepted() {
        assertThat(ConfiguredCredentialProvider.isPrivateIpv4Literal("10.0.0.1")).isTrue();
        assertThat(ConfiguredCredentialProvider.isPrivateIpv4Literal("172.16.0.1")).isTrue();
        assertThat(ConfiguredCredentialProvider.isPrivateIpv4Literal("172.31.54.3")).isTrue();
        assertThat(ConfiguredCredentialProvider.isPrivateIpv4Literal("192.168.1.10")).isTrue();
    }

    @Test
    void theDestinationMustStayInsideTheAllowedDestinations() {
        EnterpriseSourcesProperties p = new EnterpriseSourcesProperties(List.of("10.0.0.9"), List.of(PORT), List.of(
                new EnterpriseSourcesProperties.Source(TENANT, SOURCE, HOST, PORT, "witsml", "reader", "s3cret",
                        "disable", 2, null, valid())));
        assertThatThrownBy(() -> provider(p, CAPABLE, DEV)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed destinations");
    }

    @Test
    void theBindingMustDescribeExactlyTheConfiguredDestination() {
        String[][] variants = {
                {"172.31.54.4", "9999", "witsml", "reader", "disable", "EX-TLS-1", EXPIRES.toString()},
                {HOST, "5432", "witsml", "reader", "disable", "EX-TLS-1", EXPIRES.toString()},
                {HOST, "9999", "other", "reader", "disable", "EX-TLS-1", EXPIRES.toString()},
                {HOST, "9999", "witsml", "writer", "disable", "EX-TLS-1", EXPIRES.toString()},
                {HOST, "9999", "witsml", "reader", "require", "EX-TLS-1", EXPIRES.toString()},
                {HOST, "9999", "witsml", "reader", "disable", "EX-TLS-9", EXPIRES.toString()},
                {HOST, "9999", "witsml", "reader", "disable", "EX-TLS-1", EXPIRES.minusSeconds(1).toString()}};
        for (String[] v : variants) {
            EnterpriseSourcesProperties.TransportExceptionSettings t =
                    new EnterpriseSourcesProperties.TransportExceptionSettings("EX-TLS-1", EXPIRES.toString(),
                            binding(v[0], Integer.parseInt(v[1]), v[2], v[3], v[4], v[5], Instant.parse(v[6])));
            assertThatThrownBy(() -> provider(properties(HOST, "disable", t), CAPABLE, DEV)).as(String.join(",", v))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("binding");
        }
    }

    @Test
    void f4TheExpiryMustBeInTheFutureAndAtMostFourteenDaysAfterTheBuild() {
        Instant past = NOW.minusSeconds(1);
        assertThatThrownBy(() -> provider(properties(HOST, "disable", settings(past)), CAPABLE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");
        Instant tooLate = BUILT.plus(TransportPolicy.MAX_VALIDITY).plusSeconds(1);
        assertThatThrownBy(() -> provider(properties(HOST, "disable", settings(tooLate)), CAPABLE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("14 days");
        Instant limit = BUILT.plus(TransportPolicy.MAX_VALIDITY);
        assertThat(provider(properties(HOST, "disable", settings(limit)), CAPABLE, DEV).resolve(TENANT, SOURCE)
                .orElseThrow().transportException().expiresAt()).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026-11-17", "17/11/2026", "not-a-date"})
    void aMalformedExpiryIsAStartupError(String expiresAt) {
        EnterpriseSourcesProperties.TransportExceptionSettings t =
                new EnterpriseSourcesProperties.TransportExceptionSettings("EX-TLS-1", expiresAt, "a".repeat(64));
        assertThatThrownBy(() -> provider(properties(HOST, "disable", t), CAPABLE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expires-at");
    }

    @Test
    void anEmptyOrMalformedAuthorizationIsAStartupError() {
        assertThatThrownBy(() -> provider(properties(HOST, "disable",
                new EnterpriseSourcesProperties.TransportExceptionSettings(null, null, null)), CAPABLE, DEV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> provider(properties(HOST, "disable",
                new EnterpriseSourcesProperties.TransportExceptionSettings("ex tls", EXPIRES.toString(),
                        valid().binding())), CAPABLE, DEV)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void errorsNeverContainTheSecret() {
        assertThatThrownBy(() -> provider(properties(HOST, "disable", valid()), TransportCapability.NONE, DEV))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cret"));
    }

    private static EnterpriseSourcesProperties.TransportExceptionSettings settings(Instant expires) {
        return new EnterpriseSourcesProperties.TransportExceptionSettings("EX-TLS-1", expires.toString(),
                binding(HOST, PORT, "witsml", "reader", "disable", "EX-TLS-1", expires));
    }
}
