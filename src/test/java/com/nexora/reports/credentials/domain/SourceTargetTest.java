package com.nexora.reports.credentials.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B10-O3 (D-O3-2): the destination of a data source carries no secret, and its equality is the identity of the
 * destination and of how Reports connects to it.
 */
class SourceTargetTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    private static DataSourceCredentials credentials(String password) {
        return new DataSourceCredentials(TENANT, SOURCE, "db.example", 5432, "bench", "reader", password,
                "verify-full", 4, "/run/secrets/ca.crt");
    }

    @Test
    void itHasNoComponentThatCouldHoldASecret() {
        List<String> names = Arrays.stream(SourceTarget.class.getRecordComponents()).map(RecordComponent::getName)
                .toList();

        assertThat(names).containsExactly("tenantId", "dataSourceId", "host", "port", "database", "username",
                "sslMode", "maxConcurrent", "sslRootCert");
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertThat(lower).doesNotContain("password", "secret", "token", "key", "hash");
        }
        assertThat(credentials("s3cret-B10-O3").target().toString()).doesNotContain("s3cret-B10-O3");
    }

    @Test
    void theTargetOfCredentialsIsTheirDestinationWithoutThePassword() {
        SourceTarget target = credentials("one").target();

        assertThat(target).isEqualTo(new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader",
                "verify-full", 4, "/run/secrets/ca.crt"));
        assertThat(credentials("two").target()).as("the password is not part of the destination").isEqualTo(target);
        assertThat(credentials("two").target().hashCode()).isEqualTo(target.hashCode());
    }

    @Test
    void everyComponentIsPartOfTheIdentity() {
        SourceTarget base = new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader", "verify-full", 4,
                null);

        for (SourceTarget other : List.of(
                new SourceTarget(UUID.randomUUID(), SOURCE, "db.example", 5432, "bench", "reader", "verify-full", 4,
                        null),
                new SourceTarget(TENANT, UUID.randomUUID(), "db.example", 5432, "bench", "reader", "verify-full", 4,
                        null),
                new SourceTarget(TENANT, SOURCE, "db2.example", 5432, "bench", "reader", "verify-full", 4, null),
                new SourceTarget(TENANT, SOURCE, "DB.EXAMPLE", 5432, "bench", "reader", "verify-full", 4, null),
                new SourceTarget(TENANT, SOURCE, "db.example", 5433, "bench", "reader", "verify-full", 4, null),
                new SourceTarget(TENANT, SOURCE, "db.example", 5432, "erp", "reader", "verify-full", 4, null),
                new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "writer", "verify-full", 4, null),
                new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader", "require", 4, null),
                new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader", "verify-full", 2, null),
                new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader", "verify-full", 4,
                        "/run/secrets/ca.crt"))) {
            assertThat(other).as(other.toString()).isNotEqualTo(base);
        }
        assertThat(new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader", "verify-full", 4, null))
                .isEqualTo(base);
    }

    @Test
    void anIncompleteOrImpossibleDestinationIsRejected() {
        assertThatThrownBy(() -> new SourceTarget(TENANT, SOURCE, null, 5432, "bench", "reader", "verify-full", 4,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceTarget(TENANT, SOURCE, "db.example", 0, "bench", "reader", "verify-full",
                4, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceTarget(TENANT, SOURCE, "db.example", 5432, "bench", "reader",
                "verify-full", 0, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
