package com.nexora.reports.tenancy.application;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.Requester.PrincipalType;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.tenancy.InMemoryTenancyRegistry;
import com.nexora.reports.tenancy.application.port.TenancyCapture;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyException;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyRules;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * EX-TLS-1 in registration and approval: the transport is validated before connecting and with the session, stored
 * with the version and required again, identical and not expired, at approval. Every A4-B5 rule still applies.
 */
class TenancyRegistrationServiceTransportTest {

    private static final UUID TENANT = UUID.fromString("e7100000-0000-4000-8000-000000000021");
    private static final UUID SOURCE = UUID.fromString("e7100000-0000-4000-8000-000000000022");
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity("172.31.54.3", 9999, "witsml", 16384,
            "7312345678901234567", "reader");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final TransportException EX = new TransportException("EX-TLS-1",
            Instant.parse("2026-11-17T23:59:59Z"), "c".repeat(64));

    private final InMemoryTenancyRegistry registry = new InMemoryTenancyRegistry();
    private final MutableClock clock = new MutableClock(NOW);
    private final AtomicInteger captures = new AtomicInteger();
    private TenancyCapture.Captured captured = new TenancyCapture.Captured(IDENTITY, List.of("witsml_server_14"),
            false, List.of());
    private String sslMode = "disable";
    private TransportException configured = EX;

    private final TenancyRegistrationService service = new TenancyRegistrationService(registry, c -> {
        captures.incrementAndGet();
        return captured;
    }, (tenant, source) -> tenant.equals(TENANT) && source.equals(SOURCE)
            ? Optional.of(new DataSourceCredentials(tenant, source, "172.31.54.3", 9999, "witsml", "reader", "s3cret",
            sslMode, 2, null, configured))
            : Optional.empty(), ContractFixtures.SHA256, clock);

    private static Requester person(String subject) {
        return new Requester(subject, PrincipalType.HUMAN, TENANT);
    }

    private static TenancyException.Reason reason(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(TenancyException.class);
        assertThat(t.getMessage()).doesNotContain("s3cret");
        return ((TenancyException) t).reason();
    }

    private TenancyRegistration register() {
        return service.register(person("ana"), SOURCE, TenancyModel.DEDICATED_SCHEMAS, List.of("witsml_server_14"));
    }

    @Test
    void underTheExceptionTheVersionRecordsItAndItsFingerprintIncludesIt() {
        TenancyRegistration v1 = register();

        assertThat(v1.transportException()).isEqualTo(EX);
        assertThat(v1.fingerprint()).isEqualTo(TenancyRules.fingerprint(TENANT, SOURCE, 1,
                TenancyModel.DEDICATED_SCHEMAS, IDENTITY, List.of("witsml_server_14"), "ana", EX,
                ContractFixtures.SHA256));
        assertThat(v1.fingerprint()).isNotEqualTo(TenancyRules.fingerprint(TENANT, SOURCE, 1,
                TenancyModel.DEDICATED_SCHEMAS, IDENTITY, List.of("witsml_server_14"), "ana",
                ContractFixtures.SHA256));

        service.approve(person("luis"), v1.id());

        assertThat(service.active(TENANT, SOURCE).orElseThrow().registration()).isEqualTo(v1);
    }

    @Test
    void underTheExceptionTheEncryptionReportedByTheServerIsIgnoredEitherWay() {
        captured = new TenancyCapture.Captured(IDENTITY, List.of("witsml_server_14"), true, List.of());
        assertThat(register().transportException()).isEqualTo(EX);
    }

    @Test
    void withoutExceptionPlaintextIsRefusedBeforeAnyConnection() {
        configured = null;

        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.TLS_REQUIRED);
        assertThat(captures).hasValue(0);
    }

    @Test
    void withoutExceptionVerifyFullStillNeedsAnEncryptedSessionAndStoresNoException() {
        configured = null;
        sslMode = "verify-full";
        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.TLS_REQUIRED);

        captured = new TenancyCapture.Captured(IDENTITY, List.of("witsml_server_14"), true, List.of());
        TenancyRegistration v1 = register();
        assertThat(v1.transportException()).isNull();
        assertThat(v1.fingerprint()).isEqualTo(TenancyRules.fingerprint(TENANT, SOURCE, 1,
                TenancyModel.DEDICATED_SCHEMAS, IDENTITY, List.of("witsml_server_14"), "ana",
                ContractFixtures.SHA256));
    }

    @Test
    void anExpiredExceptionIsRefusedBeforeConnecting() {
        clock.set(EX.expiresAt());

        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.TRANSPORT_EXCEPTION_EXPIRED);
        assertThat(captures).hasValue(0);
    }

    @Test
    void approvalIsRefusedOnceTheExceptionExpired() {
        TenancyRegistration v1 = register();
        clock.set(EX.expiresAt().plusSeconds(1));

        assertThat(reason(() -> service.approve(person("luis"), v1.id())))
                .isEqualTo(TenancyException.Reason.TRANSPORT_EXCEPTION_EXPIRED);
        assertThat(service.active(TENANT, SOURCE)).isEmpty();
    }

    @Test
    void approvalIsRefusedWhenTheConfiguredExceptionIsNotTheOneOfTheVersion() {
        TenancyRegistration v1 = register();

        configured = new TransportException("EX-TLS-2", EX.expiresAt(), EX.binding());
        assertThat(reason(() -> service.approve(person("luis"), v1.id())))
                .isEqualTo(TenancyException.Reason.TRANSPORT_EXCEPTION_MISMATCH);

        configured = null;
        sslMode = "verify-full";
        assertThat(reason(() -> service.approve(person("luis"), v1.id())))
                .isEqualTo(TenancyException.Reason.TRANSPORT_EXCEPTION_MISMATCH);
        assertThat(service.active(TENANT, SOURCE)).isEmpty();
    }

    @Test
    void theRulesOfTenancyStillApplyUnderTheException() {
        assertThat(reason(() -> service.register(person("ana"), SOURCE, TenancyModel.SHARED_ROWS,
                List.of("witsml_server_14")))).isEqualTo(TenancyException.Reason.MODEL_NOT_SUPPORTED);
        assertThat(reason(() -> service.register(new Requester("svc", PrincipalType.SERVICE, TENANT), SOURCE,
                TenancyModel.DEDICATED_SCHEMAS, List.of("witsml_server_14"))))
                .isEqualTo(TenancyException.Reason.NOT_A_PERSON);
        assertThat(reason(() -> service.register(person("ana"), SOURCE, TenancyModel.DEDICATED_SCHEMAS,
                List.of("public")))).isEqualTo(TenancyException.Reason.CAPTURE_MISMATCH);

        captured = new TenancyCapture.Captured(IDENTITY, List.of("witsml_server_14"), false,
                List.of("pg_read_all_data"));
        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.CAPTURE_MISMATCH);

        captured = new TenancyCapture.Captured(IDENTITY, List.of("witsml_server_14"), false, List.of());
        TenancyRegistration v1 = register();
        assertThat(reason(() -> service.approve(person("ana"), v1.id())))
                .isEqualTo(TenancyException.Reason.SAME_PERSON);
    }

    /** A clock that the test moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
