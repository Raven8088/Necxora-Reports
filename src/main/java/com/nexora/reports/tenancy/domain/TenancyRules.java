package com.nexora.reports.tenancy.domain;

import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.domain.Sha256;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure rules of the tenancy registry (D-B5-1, D-B5-2): who may register and approve, which status each version has
 * and the fingerprint of a version.
 */
public final class TenancyRules {

    private TenancyRules() {
    }

    /** Registering, approving and revoking are acts of a person of the same tenant, never of a service or an AI. */
    public static void requirePerson(Requester requester) {
        if (requester.type() != Requester.PrincipalType.HUMAN) {
            throw new TenancyException(TenancyException.Reason.NOT_A_PERSON, "only a person may do this");
        }
    }

    /** Only an executable model can be registered: {@code SHARED_ROWS} is refused (P-1). */
    public static void requireExecutableModel(TenancyModel model) {
        if (!model.executable()) {
            throw new TenancyException(TenancyException.Reason.MODEL_NOT_SUPPORTED, "model not supported in A4");
        }
    }

    /**
     * The approver must be a person of the registration's tenant other than its registrant, and the version must be
     * pending and newer than the active one (an older pending version cannot replace a newer approval).
     */
    public static void requireApprovable(Requester approver, TenancyRecord target, List<TenancyRecord> history) {
        requirePerson(approver);
        TenancyRegistration registration = target.registration();
        if (!approver.tenantId().equals(registration.tenantId())) {
            throw new TenancyException(TenancyException.Reason.NOT_FOUND, "registration of another tenant");
        }
        if (approver.subject().equals(registration.registeredBy())) {
            throw new TenancyException(TenancyException.Reason.SAME_PERSON, "the registrant cannot approve");
        }
        if (target.status() != TenancyStatus.PENDING) {
            throw new TenancyException(TenancyException.Reason.NOT_PENDING, "version is not pending");
        }
        Optional<TenancyRecord> active = active(history);
        if (active.isPresent() && active.get().registration().version() > registration.version()) {
            throw new TenancyException(TenancyException.Reason.NOT_PENDING, "a newer version is active");
        }
    }

    /**
     * Statuses of all versions of one (tenant, data source): a revoked version is {@code REVOKED}; among the
     * approved ones the newest is {@code ACTIVE} and the others {@code SUPERSEDED}; the rest are {@code PENDING}.
     */
    public static List<TenancyRecord> history(List<TenancyRegistration> registrations, List<TenancyDecision> decisions) {
        Integer newestApproved = null;
        for (TenancyRegistration r : registrations) {
            if (has(decisions, r.id(), TenancyDecision.Kind.APPROVED) && !has(decisions, r.id(),
                    TenancyDecision.Kind.REVOKED) && (newestApproved == null || r.version() > newestApproved)) {
                newestApproved = r.version();
            }
        }
        List<TenancyRecord> records = new ArrayList<>();
        for (TenancyRegistration r : registrations.stream()
                .sorted(Comparator.comparingInt(TenancyRegistration::version)).toList()) {
            List<TenancyDecision> own = decisions.stream().filter(d -> d.registrationId().equals(r.id())).toList();
            TenancyStatus status;
            if (has(own, r.id(), TenancyDecision.Kind.REVOKED)) {
                status = TenancyStatus.REVOKED;
            } else if (has(own, r.id(), TenancyDecision.Kind.APPROVED)) {
                status = newestApproved != null && r.version() == newestApproved ? TenancyStatus.ACTIVE
                        : TenancyStatus.SUPERSEDED;
            } else {
                status = TenancyStatus.PENDING;
            }
            records.add(new TenancyRecord(r, own, status));
        }
        return records;
    }

    public static Optional<TenancyRecord> active(List<TenancyRecord> history) {
        return history.stream().filter(r -> r.status() == TenancyStatus.ACTIVE).findFirst();
    }

    public static int nextVersion(List<TenancyRecord> history) {
        return history.stream().mapToInt(r -> r.registration().version()).max().orElse(0) + 1;
    }

    /** SHA-256 of the canonical JSON of everything a version asserts (not of its id or registration time). */
    public static String fingerprint(UUID tenantId, UUID dataSourceId, int version, TenancyModel model,
                                     DatabaseIdentity identity, List<String> schemas, String registeredBy,
                                     Sha256 sha256) {
        return fingerprint(tenantId, dataSourceId, version, model, identity, schemas, registeredBy, null, sha256);
    }

    /**
     * EX-TLS-1: the same fingerprint, plus the transport exception when the version was captured under one. Without
     * exception the content, and therefore the fingerprint, is exactly the one of A4-B5.
     */
    public static String fingerprint(UUID tenantId, UUID dataSourceId, int version, TenancyModel model,
                                     DatabaseIdentity identity, List<String> schemas, String registeredBy,
                                     TransportException transportException, Sha256 sha256) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tenantId", tenantId.toString());
        content.put("dataSourceId", dataSourceId.toString());
        content.put("version", (long) version);
        content.put("model", model.name());
        content.put("host", identity.host());
        content.put("port", (long) identity.port());
        content.put("databaseName", identity.databaseName());
        content.put("databaseOid", identity.databaseOid());
        content.put("systemIdentifier", identity.systemIdentifier());
        content.put("role", identity.role());
        content.put("schemas", List.copyOf(new java.util.TreeSet<>(schemas)));
        content.put("registeredBy", registeredBy);
        if (transportException != null) {
            content.put("transportExceptionId", transportException.id());
            content.put("transportExceptionExpiresAt", transportException.expiresAt().toString());
            content.put("transportExceptionBinding", transportException.binding());
        }
        return CanonicalJson.sha256(content, sha256);
    }

    private static boolean has(List<TenancyDecision> decisions, UUID registrationId, TenancyDecision.Kind kind) {
        return decisions.stream().anyMatch(d -> d.registrationId().equals(registrationId) && d.kind() == kind);
    }
}
