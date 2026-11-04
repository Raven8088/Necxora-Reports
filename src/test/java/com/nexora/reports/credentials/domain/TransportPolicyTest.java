package com.nexora.reports.credentials.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EX-TLS-1: the single transport decision (TransportPolicy), positive and negative per control. */
class TransportPolicyTest {

    private static final UUID TENANT = UUID.fromString("e7100000-0000-4000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("e7100000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final TransportException EXCEPTION = new TransportException("EX-TLS-1",
            Instant.parse("2026-11-17T00:00:00Z"), "a".repeat(64));

    private static DataSourceCredentials credentials(String sslMode, TransportException exception) {
        return new DataSourceCredentials(TENANT, SOURCE, "10.20.30.40", 9999, "erp", "reader", "s3cret", sslMode, 2,
                null, exception);
    }

    private static TransportPolicy.Refusal refusal(Runnable r) {
        try {
            r.run();
        } catch (TransportPolicy.RefusedException e) {
            assertThat(e.getMessage()).doesNotContain("s3cret", "10.20.30.40", "reader");
            return e.refusal();
        }
        throw new AssertionError("not refused");
    }

    @Test
    void withoutExceptionVerifyFullWithAnEncryptedSessionIsTheOnlyAcceptedTransport() {
        assertThat(TransportPolicy.assess(credentials("verify-full", null), true, NOW)).isEmpty();
        TransportPolicy.requireConnectable(credentials("verify-full", null), NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "require", "verify-ca"})
    void withoutExceptionAnyOtherModeIsRefusedBeforeAndAfterConnecting(String mode) {
        assertThat(refusal(() -> TransportPolicy.requireConnectable(credentials(mode, null), NOW)))
                .isEqualTo(TransportPolicy.Refusal.TLS_REQUIRED);
        assertThat(refusal(() -> TransportPolicy.assess(credentials(mode, null), true, NOW)))
                .isEqualTo(TransportPolicy.Refusal.TLS_REQUIRED);
    }

    @Test
    void withoutExceptionAnUnencryptedSessionIsRefusedEvenWithVerifyFull() {
        assertThat(refusal(() -> TransportPolicy.assess(credentials("verify-full", null), false, NOW)))
                .isEqualTo(TransportPolicy.Refusal.TLS_REQUIRED);
    }

    @Test
    void withTheExceptionPlaintextIsAcceptedAndTheReportedEncryptionIsIgnored() {
        assertThat(TransportPolicy.assess(credentials("disable", EXCEPTION), false, NOW)).contains(EXCEPTION);
        assertThat(TransportPolicy.assess(credentials("disable", EXCEPTION), true, NOW)).contains(EXCEPTION);
        TransportPolicy.requireConnectable(credentials("disable", EXCEPTION), NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"allow", "prefer", "require", "verify-ca", "verify-full"})
    void theExceptionNeverCombinesWithAnotherMode(String mode) {
        assertThat(refusal(() -> TransportPolicy.assess(credentials(mode, EXCEPTION), true, NOW)))
                .isEqualTo(TransportPolicy.Refusal.TLS_REQUIRED);
        assertThat(refusal(() -> TransportPolicy.requireConnectable(credentials(mode, EXCEPTION), NOW)))
                .isEqualTo(TransportPolicy.Refusal.TLS_REQUIRED);
    }

    @Test
    void anExpiredExceptionIsRefusedFromItsExpiryInstant() {
        Instant expiry = EXCEPTION.expiresAt();
        TransportPolicy.assess(credentials("disable", EXCEPTION), false, expiry.minusNanos(1));
        assertThat(refusal(() -> TransportPolicy.assess(credentials("disable", EXCEPTION), false, expiry)))
                .isEqualTo(TransportPolicy.Refusal.EXCEPTION_EXPIRED);
        assertThat(refusal(() -> TransportPolicy.requireConnectable(credentials("disable", EXCEPTION),
                expiry.plusSeconds(1)))).isEqualTo(TransportPolicy.Refusal.EXCEPTION_EXPIRED);
    }

    @Test
    void registrationAndCurrentTransportMustBeTheSameAuthorization() {
        TransportPolicy.requireSame(null, null);
        TransportPolicy.requireSame(EXCEPTION, new TransportException("EX-TLS-1", EXCEPTION.expiresAt(),
                EXCEPTION.binding()));
        assertThat(refusal(() -> TransportPolicy.requireSame(null, EXCEPTION)))
                .isEqualTo(TransportPolicy.Refusal.EXCEPTION_MISMATCH);
        assertThat(refusal(() -> TransportPolicy.requireSame(EXCEPTION, null)))
                .isEqualTo(TransportPolicy.Refusal.EXCEPTION_MISMATCH);
        assertThat(refusal(() -> TransportPolicy.requireSame(EXCEPTION,
                new TransportException("EX-TLS-2", EXCEPTION.expiresAt(), EXCEPTION.binding()))))
                .isEqualTo(TransportPolicy.Refusal.EXCEPTION_MISMATCH);
        assertThat(refusal(() -> TransportPolicy.requireSame(EXCEPTION,
                new TransportException("EX-TLS-1", EXCEPTION.expiresAt().plusSeconds(1), EXCEPTION.binding()))))
                .isEqualTo(TransportPolicy.Refusal.EXCEPTION_MISMATCH);
        assertThat(refusal(() -> TransportPolicy.requireSame(EXCEPTION,
                new TransportException("EX-TLS-1", EXCEPTION.expiresAt(), "b".repeat(64)))))
                .isEqualTo(TransportPolicy.Refusal.EXCEPTION_MISMATCH);
    }

    @Test
    void theMaximumValidityIsFourteenDaysAndTheProfileIsExplicit() {
        assertThat(TransportPolicy.MAX_VALIDITY.toDays()).isEqualTo(14);
        assertThat(TransportPolicy.DEV_PROFILE).isEqualTo("insecure-transport-dev");
    }
}
