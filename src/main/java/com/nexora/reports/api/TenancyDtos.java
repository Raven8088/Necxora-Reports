package com.nexora.reports.api;

import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRecord;
import com.nexora.reports.tenancy.domain.TenancyRegistration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A4-B9 (D-B9-6): HTTP contract of the tenancy of a data source. A request never contains a tenant, credentials or
 * connection details: the tenant comes from the token, and what the database is and which schemas the read-only role
 * can use are captured from the database itself. The answer shows what was captured, because that is exactly what
 * the second person approves.
 */
public final class TenancyDtos {

    private TenancyDtos() {
    }

    /**
     * @param model   {@code DEDICATED_DATABASE} or {@code DEDICATED_SCHEMAS} ({@code SHARED_ROWS} is refused)
     * @param schemas exactly the schemas the read-only role can use, {@code public} included if it can
     */
    public record RegisterRequest(TenancyModel model, List<String> schemas) {
    }

    public record DatabaseResponse(String host, int port, String databaseName, long databaseOid,
                                   String systemIdentifier, String role) {
    }

    public record DecisionResponse(UUID id, UUID registrationId, TenancyDecision.Kind kind, String decidedBy,
                                   Instant decidedAt) {
        static DecisionResponse from(TenancyDecision d) {
            return new DecisionResponse(d.id(), d.registrationId(), d.kind(), d.decidedBy(), d.decidedAt());
        }
    }

    public record RegistrationResponse(UUID id, UUID dataSourceId, int version, TenancyModel model,
                                       DatabaseResponse database, List<String> schemas, String registeredBy,
                                       Instant registeredAt, String fingerprint, String status,
                                       List<DecisionResponse> decisions) {
        static RegistrationResponse pending(TenancyRegistration r) {
            return of(r, "PENDING", List.of());
        }

        static RegistrationResponse from(TenancyRecord record) {
            return of(record.registration(), record.status().name(),
                    record.decisions().stream().map(DecisionResponse::from).toList());
        }

        private static RegistrationResponse of(TenancyRegistration r, String status, List<DecisionResponse> decisions) {
            return new RegistrationResponse(r.id(), r.dataSourceId(), r.version(), r.model(),
                    new DatabaseResponse(r.identity().host(), r.identity().port(), r.identity().databaseName(),
                            r.identity().databaseOid(), r.identity().systemIdentifier(), r.identity().role()),
                    r.schemas(), r.registeredBy(), r.registeredAt(), r.fingerprint(), status, decisions);
        }
    }
}
