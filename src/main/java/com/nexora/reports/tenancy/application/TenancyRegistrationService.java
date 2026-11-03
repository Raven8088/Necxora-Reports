package com.nexora.reports.tenancy.application;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.tenancy.application.port.TenancyCapture;
import com.nexora.reports.tenancy.application.port.TenancyRegistry;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyException;
import com.nexora.reports.tenancy.domain.TenancyException.Reason;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRecord;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyRules;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Registration, approval and revocation of the tenancy of a data source (D-B5-1, D-B5-2, P-1b, P-1+ d). The tenant
 * always comes from the authenticated requester, never from an argument. HTTP endpoints and Spring wiring come in B9.
 * <ul>
 *   <li>register: a person declares the model and the schemas; the identity and the schemas are captured from the
 *       database with the read-only credentials and must match the declaration exactly; the version is pending;</li>
 *   <li>approve: another person of the same tenant approves a pending version newer than the active one;</li>
 *   <li>revoke: a person of the tenant revokes a version; executions against it are refused from then on.</li>
 * </ul>
 * After a legitimate migration or restore the checks fail and executions are refused until a new version is
 * registered and approved; older versions stay, and the data source id (and so Semantic's knowledge) is unchanged.
 */
public class TenancyRegistrationService {

    private final TenancyRegistry registry;
    private final TenancyCapture capture;
    private final CredentialProvider credentials;
    private final Sha256 sha256;
    private final Clock clock;

    public TenancyRegistrationService(TenancyRegistry registry, TenancyCapture capture,
                                      CredentialProvider credentials, Sha256 sha256, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TenancyRegistration register(Requester registrant, UUID dataSourceId, TenancyModel model,
                                        List<String> declaredSchemas) {
        TenancyRules.requirePerson(registrant);
        TenancyRules.requireExecutableModel(model);
        DataSourceCredentials source = credentials.resolve(registrant.tenantId(), dataSourceId)
                .orElseThrow(() -> new TenancyException(Reason.NO_CREDENTIALS, "no credentials for the source"));
        if (!"verify-full".equals(source.sslMode())) {
            throw new TenancyException(Reason.TLS_REQUIRED, "sslmode is not verify-full");
        }
        TenancyCapture.Captured captured = capture.capture(source);
        if (!captured.encrypted()) {
            throw new TenancyException(Reason.TLS_REQUIRED, "session is not encrypted");
        }
        if (!captured.forbiddenRoles().isEmpty()) {
            throw new TenancyException(Reason.CAPTURE_MISMATCH, "the role has access beyond schema grants");
        }
        if (!new TreeSet<>(declaredSchemas).equals(new TreeSet<>(captured.schemas()))) {
            throw new TenancyException(Reason.CAPTURE_MISMATCH, "declared schemas differ from the role's scope");
        }
        List<TenancyRecord> history = history(registrant.tenantId(), dataSourceId);
        int version = TenancyRules.nextVersion(history);
        TenancyRegistration registration = new TenancyRegistration(UUID.randomUUID(), registrant.tenantId(),
                dataSourceId, version, model, captured.identity(), captured.schemas(), registrant.subject(),
                clock.instant(), TenancyRules.fingerprint(registrant.tenantId(), dataSourceId, version, model,
                captured.identity(), captured.schemas(), registrant.subject(), sha256));
        registry.insert(registration);
        return registration;
    }

    public TenancyDecision approve(Requester approver, UUID registrationId) {
        TenancyRules.requirePerson(approver);
        TenancyRegistration registration = find(approver, registrationId);
        List<TenancyRecord> history = history(registration.tenantId(), registration.dataSourceId());
        TenancyRecord target = history.stream().filter(r -> r.registration().id().equals(registrationId))
                .findFirst().orElseThrow();
        TenancyRules.requireApprovable(approver, target, history);
        TenancyDecision decision = new TenancyDecision(UUID.randomUUID(), registrationId,
                TenancyDecision.Kind.APPROVED, approver.subject(), clock.instant());
        registry.insert(decision);
        return decision;
    }

    public TenancyDecision revoke(Requester requester, UUID registrationId) {
        TenancyRules.requirePerson(requester);
        TenancyRegistration registration = find(requester, registrationId);
        boolean revoked = registry.decisions(registration.tenantId(), registration.dataSourceId()).stream()
                .anyMatch(d -> d.registrationId().equals(registrationId) && d.kind() == TenancyDecision.Kind.REVOKED);
        if (revoked) {
            throw new TenancyException(Reason.NOT_PENDING, "already revoked");
        }
        TenancyDecision decision = new TenancyDecision(UUID.randomUUID(), registrationId,
                TenancyDecision.Kind.REVOKED, requester.subject(), clock.instant());
        registry.insert(decision);
        return decision;
    }

    /** The version executions of (tenant, data source) must be checked against, if any. */
    public Optional<TenancyRecord> active(UUID tenantId, UUID dataSourceId) {
        return TenancyRules.active(history(tenantId, dataSourceId));
    }

    public List<TenancyRecord> history(UUID tenantId, UUID dataSourceId) {
        return TenancyRules.history(registry.registrations(tenantId, dataSourceId),
                registry.decisions(tenantId, dataSourceId));
    }

    private TenancyRegistration find(Requester requester, UUID registrationId) {
        return registry.registration(requester.tenantId(), registrationId)
                .orElseThrow(() -> new TenancyException(Reason.NOT_FOUND, "no such registration for the tenant"));
    }
}
