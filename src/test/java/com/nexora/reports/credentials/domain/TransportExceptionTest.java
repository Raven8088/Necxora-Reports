package com.nexora.reports.credentials.domain;

import com.nexora.reports.plan2.infrastructure.JdkSha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-TLS-1: the binding of the authorized destination. The golden value is the one
 * {@code nexora-deploy/scripts/New-NexoraTransportException.ps1} computes for the same input.
 */
class TransportExceptionTest {

    static final UUID TENANT = UUID.fromString("e7100000-0000-4000-8000-000000000001");
    static final UUID SOURCE = UUID.fromString("e7100000-0000-4000-8000-000000000002");
    static final Instant EXPIRES = Instant.parse("2026-11-17T23:59:59Z");
    /** SHA-256 of the canonical text below, computed independently (Python hashlib and PowerShell). */
    static final String GOLDEN = "cd1b922610d9dacf9feeca572960b679d89b93ef9d5779376f0698d9fd43e908";

    @Test
    void theCanonicalTextIsStableAndHasNoSecret() {
        assertThat(TransportException.canonical(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "disable",
                "EX-TLS-1", EXPIRES)).isEqualTo("""
                nexora-reports-transport-exception/1
                tenantId=e7100000-0000-4000-8000-000000000001
                dataSourceId=e7100000-0000-4000-8000-000000000002
                host=10.20.30.40
                port=9999
                database=erp
                username=reader
                sslMode=disable
                id=EX-TLS-1
                expiresAt=2026-11-17T23:59:59Z
                """);
    }

    @Test
    void theBindingIsTheSha256OfTheCanonicalText() {
        assertThat(TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "disable",
                "EX-TLS-1", EXPIRES, new JdkSha256())).isEqualTo(GOLDEN);
    }

    @Test
    void everyElementOfTheDestinationChangesTheBinding() {
        JdkSha256 sha = new JdkSha256();
        String base = TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "disable",
                "EX-TLS-1", EXPIRES, sha);
        assertThat(java.util.List.of(
                TransportException.binding(SOURCE, SOURCE, "10.20.30.40", 9999, "erp", "reader", "disable",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, TENANT, "10.20.30.40", 9999, "erp", "reader", "disable",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.41", 9999, "erp", "reader", "disable",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.40", 5432, "erp", "reader", "disable",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "crm", "reader", "disable",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "writer", "disable",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "require",
                        "EX-TLS-1", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "disable",
                        "EX-TLS-2", EXPIRES, sha),
                TransportException.binding(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "disable",
                        "EX-TLS-1", EXPIRES.plusSeconds(1), sha))).doesNotContain(base).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ex-tls-1", "EX TLS", "-EX", "EX-TLS-1-THAT-IS-FAR-TOO-LONG-FOR-AN-ID"})
    void malformedIdsAreRejected(String id) {
        assertThatThrownBy(() -> new TransportException(id, EXPIRES, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "0123",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void malformedBindingsAreRejected(String binding) {
        assertThatThrownBy(() -> new TransportException("EX-TLS-1", EXPIRES, binding))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
