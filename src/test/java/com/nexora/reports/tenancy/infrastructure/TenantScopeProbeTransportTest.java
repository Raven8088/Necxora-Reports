package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.execution.application.port.CatalogReader;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRecord;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyStatus;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.reports.plan2.domain.TestPlans.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * EX-TLS-1 in every run (TenantScopeProbe): only the transport step is adapted. A run passes it with verify-full and
 * an encrypted session, or with the valid exception the active registration was captured with; it then goes on to
 * the unchanged identity, role, schema and relation checks (here the fake catalog stops at the first of them).
 */
class TenantScopeProbeTransportTest {

    private static final UUID TENANT = UUID.fromString("e7100000-0000-4000-8000-000000000031");
    private static final UUID SOURCE = UUID.fromString("e7100000-0000-4000-8000-000000000032");
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity("172.31.54.3", 9999, "witsml", 16384,
            "7312345678901234567", "reader");
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final TransportException EX = new TransportException("EX-TLS-1",
            Instant.parse("2026-11-17T23:59:59Z"), "d".repeat(64));

    /** Thrown by the fake catalog when the probe reaches the first check after the transport. */
    private static final class ReachedIdentityChecks extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final List<String> queries = new ArrayList<>();

    private CatalogReader catalog(boolean sessionEncrypted) {
        return (sql, parameters) -> {
            queries.add(sql);
            if (sql.contains("pg_stat_ssl")) {
                return List.of(List.of(sessionEncrypted));
            }
            throw new ReachedIdentityChecks();
        };
    }

    private static ExecutionRequestV2 request() {
        SqlQueryV2 q = new PlanV2Translator().translate(TestPlans.validated(TestPlans.oneColumn("witsml_server_14",
                "company", "name", "TEXT", "TEXT", "IS_NOT_NULL", list())), 10);
        return new ExecutionRequestV2(TENANT, SOURCE, List.of(q));
    }

    private static Optional<TenancyRecord> active(TransportException registered) {
        TenancyRegistration r = new TenancyRegistration(UUID.randomUUID(), TENANT, SOURCE, 1,
                TenancyModel.DEDICATED_SCHEMAS, IDENTITY, List.of("witsml_server_14"), "ana", NOW.minusSeconds(60),
                "e".repeat(64), registered);
        return Optional.of(new TenancyRecord(r, List.of(new TenancyDecision(UUID.randomUUID(), r.id(),
                TenancyDecision.Kind.APPROVED, "luis", NOW.minusSeconds(30))), TenancyStatus.ACTIVE));
    }

    private static DataSourceCredentials credentials(String sslMode, TransportException exception) {
        return new DataSourceCredentials(TENANT, SOURCE, "172.31.54.3", 9999, "witsml", "reader", "s3cret", sslMode,
                2, null, exception);
    }

    private Throwable verify(Optional<TenancyRecord> active, DataSourceCredentials c, boolean encrypted,
                             Instant now) {
        return catchThrowable(() -> TenantScopeProbe.forExecution(active, request(), Clock.fixed(now, ZoneOffset.UTC))
                .verify(c, catalog(encrypted)));
    }

    private static void assertRefused(Throwable t, String reason) {
        assertThat(t).isInstanceOf(ExecutionException.class);
        assertThat(((ExecutionException) t).code()).isEqualTo(ErrorCode.TENANT_SCOPE_UNVERIFIED);
        assertThat(t.getMessage()).isEqualTo(reason).doesNotContain("s3cret");
    }

    @Test
    void aRunUnderTheSameValidExceptionPassesTheTransportAndReachesTheTenancyChecks() {
        Throwable t = verify(active(EX), credentials("disable", EX), true, NOW);

        assertThat(t).isInstanceOf(ReachedIdentityChecks.class);
        assertThat(queries).noneMatch(q -> q.contains("pg_stat_ssl"));
    }

    @Test
    void aVerifyFullRunAgainstAVerifyFullRegistrationBehavesAsBefore() {
        assertThat(verify(active(null), credentials("verify-full", null), true, NOW))
                .isInstanceOf(ReachedIdentityChecks.class);
        assertRefused(verify(active(null), credentials("verify-full", null), false, NOW),
                "TLS with verify-full is required");
    }

    @Test
    void plaintextWithoutExceptionIsRefusedEvenIfTheServerReportsEncryption() {
        assertRefused(verify(active(null), credentials("disable", null), true, NOW), "TLS with verify-full is required");
        assertRefused(verify(active(EX), credentials("disable", null), true, NOW), "TLS with verify-full is required");
    }

    @Test
    void aPlaintextRunAgainstAVerifyFullRegistrationIsRefused() {
        assertRefused(verify(active(null), credentials("disable", EX), false, NOW),
                "the transport differs from the registration");
    }

    @Test
    void aVerifyFullRunAgainstARegistrationCapturedInPlaintextIsRefused() {
        assertRefused(verify(active(EX), credentials("verify-full", null), true, NOW),
                "the transport differs from the registration");
    }

    @Test
    void anotherExceptionThanTheOneOfTheRegistrationIsRefused() {
        TransportException other = new TransportException("EX-TLS-2", EX.expiresAt(), EX.binding());
        assertRefused(verify(active(EX), credentials("disable", other), false, NOW),
                "the transport differs from the registration");
    }

    @Test
    void anExpiredExceptionIsRefusedBeforeReadingAnything() {
        assertRefused(verify(active(EX), credentials("disable", EX), false, EX.expiresAt()),
                "the transport exception has expired");
        assertThat(queries).isEmpty();
    }

    @Test
    void withoutAnActiveRegistrationNothingPassesUnderTheException() {
        assertRefused(catchThrowable(() -> TenantScopeProbe.forExecution(Optional.empty(), request(),
                Clock.fixed(NOW, ZoneOffset.UTC)).verify(credentials("disable", EX), catalog(false))),
                "no approved tenancy registration");
    }
}
