package com.nexora.reports.tenancy.domain;

import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.Requester.PrincipalType;
import com.nexora.reports.plan2.ContractFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A4-B5: pure rules of the tenancy registry (status, four eyes, models, fingerprint). */
class TenancyRulesTest {

    private static final UUID TENANT = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("b5000000-0000-4000-8000-000000000002");
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity("db.example", 5432, "erp", 16384,
            "7312345678901234567", "reader");

    @Test
    void onlyDedicatedModelsAreExecutable() {
        assertThat(TenancyModel.DEDICATED_DATABASE.executable()).isTrue();
        assertThat(TenancyModel.DEDICATED_SCHEMAS.executable()).isTrue();
        assertThat(TenancyModel.SHARED_ROWS.executable()).isFalse();
        assertThat(reason(() -> TenancyRules.requireExecutableModel(TenancyModel.SHARED_ROWS)))
                .isEqualTo(TenancyException.Reason.MODEL_NOT_SUPPORTED);
    }

    @Test
    void onlyAPersonMayActOnTheRegistry() {
        TenancyRules.requirePerson(person("ana"));
        assertThat(reason(() -> TenancyRules.requirePerson(new Requester("svc", PrincipalType.SERVICE, TENANT))))
                .isEqualTo(TenancyException.Reason.NOT_A_PERSON);
        assertThat(reason(() -> TenancyRules.requirePerson(new Requester("bot", PrincipalType.AI, TENANT))))
                .isEqualTo(TenancyException.Reason.NOT_A_PERSON);
    }

    @Test
    void aVersionWithoutDecisionsIsPending() {
        List<TenancyRecord> history = TenancyRules.history(List.of(registration(1, "ana")), List.of());

        assertThat(history.getFirst().status()).isEqualTo(TenancyStatus.PENDING);
        assertThat(TenancyRules.active(history)).isEmpty();
        assertThat(TenancyRules.nextVersion(history)).isEqualTo(2);
    }

    @Test
    void theNewestApprovedVersionIsActiveAndOlderApprovedOnesAreSuperseded() {
        TenancyRegistration v1 = registration(1, "ana");
        TenancyRegistration v2 = registration(2, "ana");
        TenancyRegistration v3 = registration(3, "ana");

        List<TenancyRecord> history = TenancyRules.history(List.of(v3, v1, v2),
                List.of(approve(v1, "luis"), approve(v2, "luis")));

        assertThat(history.stream().map(TenancyRecord::status).toList())
                .containsExactly(TenancyStatus.SUPERSEDED, TenancyStatus.ACTIVE, TenancyStatus.PENDING);
        assertThat(TenancyRules.active(history).orElseThrow().registration()).isEqualTo(v2);
    }

    @Test
    void aRevokedVersionIsNeverActiveAndDoesNotReviveAnOlderOne() {
        TenancyRegistration v1 = registration(1, "ana");
        TenancyRegistration v2 = registration(2, "ana");
        List<TenancyDecision> decisions = new ArrayList<>(List.of(approve(v1, "luis"), approve(v2, "luis"),
                new TenancyDecision(UUID.randomUUID(), v2.id(), TenancyDecision.Kind.REVOKED, "luis", Instant.now())));

        List<TenancyRecord> history = TenancyRules.history(List.of(v1, v2), decisions);

        assertThat(history.get(1).status()).isEqualTo(TenancyStatus.REVOKED);
        assertThat(history.get(0).status()).isEqualTo(TenancyStatus.ACTIVE);
        decisions.add(new TenancyDecision(UUID.randomUUID(), v1.id(), TenancyDecision.Kind.REVOKED, "luis",
                Instant.now()));
        assertThat(TenancyRules.active(TenancyRules.history(List.of(v1, v2), decisions))).isEmpty();
    }

    @Test
    void theRegistrantCannotApproveItsOwnVersion() {
        List<TenancyRecord> history = TenancyRules.history(List.of(registration(1, "ana")), List.of());

        assertThat(reason(() -> TenancyRules.requireApprovable(person("ana"), history.getFirst(), history)))
                .isEqualTo(TenancyException.Reason.SAME_PERSON);
        TenancyRules.requireApprovable(person("luis"), history.getFirst(), history);
    }

    @Test
    void aPersonOfAnotherTenantCannotApprove() {
        List<TenancyRecord> history = TenancyRules.history(List.of(registration(1, "ana")), List.of());
        Requester outsider = new Requester("luis", PrincipalType.HUMAN, UUID.randomUUID());

        assertThat(reason(() -> TenancyRules.requireApprovable(outsider, history.getFirst(), history)))
                .isEqualTo(TenancyException.Reason.NOT_FOUND);
    }

    @Test
    void onlyAPendingVersionNewerThanTheActiveOneCanBeApproved() {
        TenancyRegistration v1 = registration(1, "ana");
        TenancyRegistration v2 = registration(2, "ana");
        List<TenancyRecord> approved = TenancyRules.history(List.of(v1), List.of(approve(v1, "luis")));
        List<TenancyRecord> newerActive = TenancyRules.history(List.of(v1, v2), List.of(approve(v2, "luis")));

        assertThat(reason(() -> TenancyRules.requireApprovable(person("eva"), approved.getFirst(), approved)))
                .isEqualTo(TenancyException.Reason.NOT_PENDING);
        assertThat(reason(() -> TenancyRules.requireApprovable(person("eva"), newerActive.getFirst(), newerActive)))
                .isEqualTo(TenancyException.Reason.NOT_PENDING);
    }

    @Test
    void theFingerprintCoversEverythingTheVersionAssertsAndIgnoresSchemaOrder() {
        String a = TenancyRules.fingerprint(TENANT, SOURCE, 1, TenancyModel.DEDICATED_SCHEMAS, IDENTITY,
                List.of("b", "a"), "ana", ContractFixtures.SHA256);
        String b = TenancyRules.fingerprint(TENANT, SOURCE, 1, TenancyModel.DEDICATED_SCHEMAS, IDENTITY,
                List.of("a", "b"), "ana", ContractFixtures.SHA256);
        String otherCluster = TenancyRules.fingerprint(TENANT, SOURCE, 1, TenancyModel.DEDICATED_SCHEMAS,
                new DatabaseIdentity("db.example", 5432, "erp", 16384, "1", "reader"), List.of("a", "b"), "ana",
                ContractFixtures.SHA256);

        assertThat(a).isEqualTo(b);
        assertThat(a.matches("[0-9a-f]{64}")).isTrue();
        assertThat(otherCluster).isNotEqualTo(a);
        assertThatThrownBy(() -> new DatabaseIdentity("h", 5432, "db", 0, "1", "r"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static Requester person(String subject) {
        return new Requester(subject, PrincipalType.HUMAN, TENANT);
    }

    private static TenancyRegistration registration(int version, String by) {
        return new TenancyRegistration(UUID.randomUUID(), TENANT, SOURCE, version, TenancyModel.DEDICATED_DATABASE,
                IDENTITY, List.of("public"), by, Instant.now(), "f".repeat(64));
    }

    private static TenancyDecision approve(TenancyRegistration registration, String by) {
        return new TenancyDecision(UUID.randomUUID(), registration.id(), TenancyDecision.Kind.APPROVED, by,
                Instant.now());
    }

    private static TenancyException.Reason reason(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(TenancyException.class);
        return ((TenancyException) thrown).reason();
    }
}
