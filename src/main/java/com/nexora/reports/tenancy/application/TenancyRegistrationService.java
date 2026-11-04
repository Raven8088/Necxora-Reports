package com.nexora.reports.tenancy.application;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.credentials.domain.TransportPolicy;
import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.tenancy.application.port.TenancyCapture;
import com.nexora.reports.tenancy.application.port.TenancyRegistry;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyException.Reason;
import com.nexora.reports.tenancy.domain.TenancyException;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRecord;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

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

    private static final Logger LOG = LoggerFactory.getLogger(TenancyRegistrationService.class);

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
        // The transport is decided before connecting (no unauthorized connection is opened) and again with the
        // session (TransportPolicy, EX-TLS-1). Without exception: verify-full and an encrypted session, as in A4-B5.
        checkTransport(() -> TransportPolicy.requireConnectable(source, clock.instant()));
        TenancyCapture.Captured captured = capture.capture(source);
        TransportException transport = transportOf(() -> TransportPolicy.assess(source, captured.encrypted(),
                clock.instant())).orElse(null);
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
                captured.identity(), captured.schemas(), registrant.subject(), transport, sha256), transport);
        registry.insert(registration);
        if (transport != null) {
            LOG.warn("EX-TLS-1: tenancy version {} of data source {} captured under transport exception {}: the"
                    + " connection is NOT encrypted and the server identity is NOT verified", version, dataSourceId,
                    transport.id());
        }
        return registration;
    }

    public TenancyDecision approve(Requester approver, UUID registrationId) {
        TenancyRules.requirePerson(approver);
        TenancyRegistration registration = find(approver, registrationId);
        List<TenancyRecord> history = history(registration.tenantId(), registration.dataSourceId());
        TenancyRecord target = history.stream().filter(r -> r.registration().id().equals(registrationId))
                .findFirst().orElseThrow();
        TenancyRules.requireApprovable(approver, target, history);
        requireSameTransport(approver, registration);
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

    /**
     * EX-TLS-1: a version captured under a transport exception can be approved only while the configured source
     * relies on that same exception, not expired. A version captured with verify-full is approved as in A4-B5.
     */
    private void requireSameTransport(Requester approver, TenancyRegistration registration) {
        TransportException registered = registration.transportException();
        if (registered == null) {
            return;
        }
        DataSourceCredentials source = credentials.resolve(approver.tenantId(), registration.dataSourceId())
                .orElseThrow(() -> new TenancyException(Reason.NO_CREDENTIALS, "no credentials for the source"));
        checkTransport(() -> {
            TransportPolicy.requireSame(registered, source.transportException());
            TransportPolicy.requireConnectable(source, clock.instant());
        });
        LOG.warn("EX-TLS-1: approving tenancy version {} of data source {} captured under transport exception {}",
                registration.version(), registration.dataSourceId(), registered.id());
    }

    private static void checkTransport(Runnable check) {
        transportOf(() -> {
            check.run();
            return null;
        });
    }

    private static <T> T transportOf(Supplier<T> check) {
        try {
            return check.get();
        } catch (TransportPolicy.RefusedException e) {
            Reason reason = switch (e.refusal()) {
                case TLS_REQUIRED -> Reason.TLS_REQUIRED;
                case EXCEPTION_EXPIRED -> Reason.TRANSPORT_EXCEPTION_EXPIRED;
                case EXCEPTION_MISMATCH -> Reason.TRANSPORT_EXCEPTION_MISMATCH;
            };
            throw new TenancyException(reason, e.getMessage());
        }
    }

    private TenancyRegistration find(Requester requester, UUID registrationId) {
        return registry.registration(requester.tenantId(), registrationId)
                .orElseThrow(() -> new TenancyException(Reason.NOT_FOUND, "no such registration for the tenant"));
    }
}
