package com.nexora.reports.tenancy.application;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.Requester.PrincipalType;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.tenancy.InMemoryTenancyRegistry;
import com.nexora.reports.tenancy.application.port.TenancyCapture;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyException;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A4-B5: registration, approval and revocation, with an in-memory registry and a controlled capture. */
class TenancyRegistrationServiceTest {

    private static final UUID TENANT = UUID.fromString("b5000000-0000-4000-8000-000000000011");
    private static final UUID OTHER_TENANT = UUID.fromString("b5000000-0000-4000-8000-000000000012");
    private static final UUID SOURCE = UUID.fromString("b5000000-0000-4000-8000-000000000013");
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity("db.example", 5432, "erp", 16384,
            "7312345678901234567", "reader");

    private final InMemoryTenancyRegistry registry = new InMemoryTenancyRegistry();
    private TenancyCapture.Captured captured = new TenancyCapture.Captured(IDENTITY, List.of("ventas", "public"),
            true, List.of());
    private String sslMode = "verify-full";
    private final TenancyRegistrationService service = new TenancyRegistrationService(registry, c -> captured,
            (tenant, source) -> tenant.equals(TENANT) && source.equals(SOURCE)
                    ? Optional.of(new DataSourceCredentials(tenant, source, "db.example", 5432, "erp", "reader",
                    "secret", sslMode, 2))
                    : Optional.empty(),
            ContractFixtures.SHA256, Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void aRegisteredVersionIsPendingUntilAnotherPersonApprovesIt() {
        TenancyRegistration v1 = service.register(person("ana"), SOURCE, TenancyModel.DEDICATED_SCHEMAS,
                List.of("public", "ventas"));

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.schemas()).containsExactly("public", "ventas");
        assertThat(v1.registeredBy()).isEqualTo("ana");
        assertThat(service.active(TENANT, SOURCE)).isEmpty();

        service.approve(person("luis"), v1.id());

        assertThat(service.active(TENANT, SOURCE).orElseThrow().registration()).isEqualTo(v1);
    }

    @Test
    void theRegistrantCannotApproveItsOwnVersion() {
        TenancyRegistration v1 = register();

        assertThat(reason(() -> service.approve(person("ana"), v1.id()))).isEqualTo(TenancyException.Reason.SAME_PERSON);
        assertThat(service.active(TENANT, SOURCE)).isEmpty();
    }

    @Test
    void servicesAndAiCannotRegisterOrApprove() {
        TenancyRegistration v1 = register();

        assertThat(reason(() -> service.register(new Requester("svc", PrincipalType.SERVICE, TENANT), SOURCE,
                TenancyModel.DEDICATED_DATABASE, List.of("public", "ventas"))))
                .isEqualTo(TenancyException.Reason.NOT_A_PERSON);
        assertThat(reason(() -> service.approve(new Requester("bot", PrincipalType.AI, TENANT), v1.id())))
                .isEqualTo(TenancyException.Reason.NOT_A_PERSON);
    }

    @Test
    void aPersonOfAnotherTenantSeesNoRegistrationAndHasNoCredentials() {
        TenancyRegistration v1 = register();
        Requester outsider = new Requester("eva", PrincipalType.HUMAN, OTHER_TENANT);

        assertThat(reason(() -> service.approve(outsider, v1.id()))).isEqualTo(TenancyException.Reason.NOT_FOUND);
        assertThat(reason(() -> service.revoke(outsider, v1.id()))).isEqualTo(TenancyException.Reason.NOT_FOUND);
        assertThat(reason(() -> service.register(outsider, SOURCE, TenancyModel.DEDICATED_DATABASE,
                List.of("public", "ventas")))).isEqualTo(TenancyException.Reason.NO_CREDENTIALS);
    }

    @Test
    void sharedRowsCanNeverBeRegistered() {
        assertThat(reason(() -> service.register(person("ana"), SOURCE, TenancyModel.SHARED_ROWS,
                List.of("public", "ventas")))).isEqualTo(TenancyException.Reason.MODEL_NOT_SUPPORTED);
    }

    @Test
    void theDeclaredSchemasMustBeExactlyThoseTheRoleCanUse() {
        assertThat(reason(() -> service.register(person("ana"), SOURCE, TenancyModel.DEDICATED_SCHEMAS,
                List.of("ventas")))).isEqualTo(TenancyException.Reason.CAPTURE_MISMATCH);
        assertThat(reason(() -> service.register(person("ana"), SOURCE, TenancyModel.DEDICATED_SCHEMAS,
                List.of("public", "ventas", "compras")))).isEqualTo(TenancyException.Reason.CAPTURE_MISMATCH);
    }

    @Test
    void aRoleWithAccessBeyondItsSchemasCannotBeRegistered() {
        captured = new TenancyCapture.Captured(IDENTITY, List.of("public", "ventas"), true,
                List.of("pg_read_all_data"));

        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.CAPTURE_MISMATCH);
    }

    @Test
    void registrationRequiresVerifyFullAndAnEncryptedSession() {
        sslMode = "require";
        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.TLS_REQUIRED);
        sslMode = "verify-full";
        captured = new TenancyCapture.Captured(IDENTITY, List.of("public", "ventas"), false, List.of());
        assertThat(reason(this::register)).isEqualTo(TenancyException.Reason.TLS_REQUIRED);
    }

    @Test
    void revalidationCreatesANewPendingVersionThatOnlyReplacesTheActiveOneWhenApproved() {
        TenancyRegistration v1 = register();
        service.approve(person("luis"), v1.id());
        captured = new TenancyCapture.Captured(new DatabaseIdentity("db.example", 5432, "erp", 99999,
                "7312345678901234567", "reader"), List.of("public", "ventas"), true, List.of());

        TenancyRegistration v2 = register();

        assertThat(v2.version()).isEqualTo(2);
        assertThat(service.active(TENANT, SOURCE).orElseThrow().registration()).isEqualTo(v1);
        service.approve(person("luis"), v2.id());
        assertThat(service.active(TENANT, SOURCE).orElseThrow().registration()).isEqualTo(v2);
        assertThat(service.history(TENANT, SOURCE).getFirst().status()).isEqualTo(TenancyStatus.SUPERSEDED);
        assertThat(reason(() -> service.approve(person("eva"), v1.id()))).isEqualTo(TenancyException.Reason.NOT_PENDING);
    }

    @Test
    void aRevokedVersionStopsBeingActiveAndCannotBeRevokedTwice() {
        TenancyRegistration v1 = register();
        service.approve(person("luis"), v1.id());

        service.revoke(person("ana"), v1.id());

        assertThat(service.active(TENANT, SOURCE)).isEmpty();
        assertThat(reason(() -> service.revoke(person("luis"), v1.id()))).isEqualTo(TenancyException.Reason.NOT_PENDING);
        assertThat(reason(() -> service.approve(person("eva"), v1.id()))).isEqualTo(TenancyException.Reason.NOT_PENDING);
    }

    // ------------------------------------------------------------------ helpers

    private TenancyRegistration register() {
        return service.register(person("ana"), SOURCE, TenancyModel.DEDICATED_DATABASE, List.of("public", "ventas"));
    }

    private static Requester person(String subject) {
        return new Requester(subject, PrincipalType.HUMAN, TENANT);
    }

    private static TenancyException.Reason reason(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(TenancyException.class);
        return ((TenancyException) thrown).reason();
    }
}
