package com.nexora.reports.credentials.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredCredentialProviderTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    @Test
    void credentialsAreKeyedByTenantAndSource() {
        var provider = new ConfiguredCredentialProvider(properties(List.of("db.internal"), List.of(5432),
                source(TENANT_A, SOURCE, "db.internal", 5432)));
        assertThat(provider.resolve(TENANT_A, SOURCE)).map(DataSourceCredentials::username).contains("reader");
        assertThat(provider.resolve(TENANT_B, SOURCE)).as("another tenant never sees it").isEmpty();
        assertThat(provider.resolve(TENANT_A, UUID.randomUUID())).isEmpty();
        assertThat(provider.resolve(TENANT_A, SOURCE).orElseThrow().toString()).doesNotContain("s3cret");
    }

    @Test
    void destinationsOutsideThePolicyAreRefusedAtStartup() {
        assertThatThrownBy(() -> new ConfiguredCredentialProvider(properties(List.of("db.internal"),
                List.of(5432), source(TENANT_A, SOURCE, "other.internal", 5432))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConfiguredCredentialProvider(properties(List.of("db.internal"),
                List.of(5432), source(TENANT_A, SOURCE, "db.internal", 22))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConfiguredCredentialProvider(properties(List.of("169.254.169.254"),
                List.of(80), source(TENANT_A, SOURCE, "169.254.169.254", 80))))
                .as("cloud metadata endpoints are never allowed").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSourceCannotBelongToTwoTenants() {
        assertThatThrownBy(() -> new ConfiguredCredentialProvider(properties(List.of("db.internal"),
                List.of(5432), source(TENANT_A, SOURCE, "db.internal", 5432),
                source(TENANT_B, SOURCE, "db.internal", 5432)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostsThatCouldCarryDriverOptionsAreRejected() {
        TargetPolicy policy = new TargetPolicy(List.of("db.internal"), List.of(5432));
        assertThat(policy.allows("db.internal", 5432)).isTrue();
        assertThat(policy.allows("DB.INTERNAL", 5432)).isTrue();
        assertThat(policy.allows("db.internal/erp?socketFactory=x", 5432)).isFalse();
        assertThat(policy.allows("user@db.internal", 5432)).isFalse();
        assertThat(policy.allows("db.internal,evil.example", 5432)).isFalse();
        assertThat(policy.allows("evil.example", 5432)).isFalse();
    }

    private static EnterpriseSourcesProperties properties(List<String> hosts, List<Integer> ports,
                                                          EnterpriseSourcesProperties.Source... sources) {
        return new EnterpriseSourcesProperties(hosts, ports, List.of(sources));
    }

    private static EnterpriseSourcesProperties.Source source(UUID tenant, UUID source, String host, int port) {
        return new EnterpriseSourcesProperties.Source(tenant, source, host, port, "erp", "reader", "s3cret",
                "require", 2);
    }
}
