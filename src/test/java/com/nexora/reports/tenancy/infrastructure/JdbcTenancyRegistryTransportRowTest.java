package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.credentials.domain.TransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-TLS-1 (correction B): the registry never reads a partial or malformed transport exception as "no exception";
 * it refuses the row as corrupt, without echoing the stored values.
 */
class JdbcTenancyRegistryTransportRowTest {

    private static final String ID = "EX-TLS-1";
    private static final Timestamp EXPIRES = Timestamp.from(Instant.parse("2026-11-17T23:59:59Z"));
    private static final String BINDING = "a".repeat(64);

    @Test
    void noColumnSetMeansCapturedWithVerifyFull() {
        assertThat(JdbcTenancyRegistry.transportException(null, null, null)).isNull();
    }

    @Test
    void allColumnsSetAndWellFormedGiveTheException() {
        assertThat(JdbcTenancyRegistry.transportException(ID, EXPIRES, BINDING))
                .isEqualTo(new TransportException(ID, EXPIRES.toInstant(), BINDING));
    }

    static Stream<Arguments> partial() {
        return Stream.of(Arguments.of(ID, null, null), Arguments.of(null, EXPIRES, null),
                Arguments.of(null, null, BINDING), Arguments.of(ID, EXPIRES, null), Arguments.of(ID, null, BINDING),
                Arguments.of(null, EXPIRES, BINDING));
    }

    @ParameterizedTest
    @MethodSource("partial")
    void aPartialExceptionIsACorruptRow(String id, Timestamp expiresAt, String binding) {
        assertThatThrownBy(() -> JdbcTenancyRegistry.transportException(id, expiresAt, binding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("corrupt tenancy registration: partial transport exception");
    }

    static Stream<Arguments> malformed() {
        return Stream.of(Arguments.of("ex-tls-1", BINDING), Arguments.of("", BINDING),
                Arguments.of("A".repeat(33), BINDING), Arguments.of(ID, "A".repeat(64)),
                Arguments.of(ID, "a".repeat(63)), Arguments.of(ID, "g".repeat(64)));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void aMalformedExceptionIsACorruptRowAndItsValuesAreNotEchoed(String id, String binding) {
        assertThatThrownBy(() -> JdbcTenancyRegistry.transportException(id, EXPIRES, binding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("corrupt tenancy registration: malformed transport exception")
                .hasNoCause();
    }
}
