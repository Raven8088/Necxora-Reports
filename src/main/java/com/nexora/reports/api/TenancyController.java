package com.nexora.reports.api;

import com.nexora.reports.api.TenancyDtos.DecisionResponse;
import com.nexora.reports.api.TenancyDtos.RegisterRequest;
import com.nexora.reports.api.TenancyDtos.RegistrationResponse;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.security.Permission;
import com.nexora.reports.tenancy.application.TenancyRegistrationService;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyException;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A4-B9 (D-B9-6, D-B5-8): registration, approval, revocation and listing of the tenancy of a data source, only by
 * persons with {@code reports:tenancy:manage}, always in the tenant of the token. Four eyes: the approver must be a
 * person other than the registrant ({@code tenancy_same_person}). Without an active registration every {@code /2}
 * run of the source is refused ({@code tenant_scope_unverified}).
 */
@RestController
public class TenancyController {

    public static final String REGISTRATIONS = "/api/v1/data-sources/{dataSourceId}/tenancy/registrations";
    public static final String APPROVAL = REGISTRATIONS + "/{registrationId}/approval";
    public static final String REVOCATION = REGISTRATIONS + "/{registrationId}/revocation";

    /** PostgreSQL identifiers: at most 63 bytes; control characters never reach the service. */
    private static final Pattern SCHEMA = Pattern.compile("[^\\p{Cntrl}]{1,63}");
    private static final int MAX_SCHEMAS = 64;

    private final TenancyRegistrationService service;

    public TenancyController(TenancyRegistrationService service) {
        this.service = service;
    }

    @PostMapping(path = REGISTRATIONS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_TENANCY_MANAGE)
    public ResponseEntity<RegistrationResponse> register(Requester requester, @PathVariable UUID dataSourceId,
                                                         @RequestBody RegisterRequest body) {
        if (body == null || body.model() == null) {
            throw new InvalidRequestException("model", "is required");
        }
        List<String> schemas = body.schemas();
        if (schemas == null || schemas.isEmpty() || schemas.size() > MAX_SCHEMAS) {
            throw new InvalidRequestException("schemas", "must list between 1 and " + MAX_SCHEMAS + " schemas");
        }
        for (String schema : schemas) {
            if (schema == null || !SCHEMA.matcher(schema).matches()) {
                throw new InvalidRequestException("schemas", "every schema must be a non-empty identifier");
            }
        }
        if (new HashSet<>(schemas).size() != schemas.size()) {
            throw new InvalidRequestException("schemas", "must not repeat a schema");
        }
        TenancyRegistration registration = service.register(requester, dataSourceId, body.model(), schemas);
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + dataSourceId
                        + "/tenancy/registrations/" + registration.id()))
                .body(RegistrationResponse.pending(registration));
    }

    @GetMapping(path = REGISTRATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_TENANCY_MANAGE)
    public List<RegistrationResponse> list(Requester requester, @PathVariable UUID dataSourceId) {
        requirePerson(requester);
        return service.history(requester.tenantId(), dataSourceId).stream().map(RegistrationResponse::from)
                .toList();
    }

    @PostMapping(path = APPROVAL, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_TENANCY_MANAGE)
    public ResponseEntity<DecisionResponse> approve(Requester requester, @PathVariable UUID dataSourceId,
                                                    @PathVariable UUID registrationId) {
        requireOfSource(requester, dataSourceId, registrationId);
        TenancyDecision decision = service.approve(requester, registrationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(DecisionResponse.from(decision));
    }

    @PostMapping(path = REVOCATION, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_TENANCY_MANAGE)
    public ResponseEntity<DecisionResponse> revoke(Requester requester, @PathVariable UUID dataSourceId,
                                                   @PathVariable UUID registrationId) {
        requireOfSource(requester, dataSourceId, registrationId);
        TenancyDecision decision = service.revoke(requester, registrationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(DecisionResponse.from(decision));
    }

    /** A registration of another data source (or tenant) is answered as one that does not exist. */
    private void requireOfSource(Requester requester, UUID dataSourceId, UUID registrationId) {
        requirePerson(requester);
        boolean ofSource = service.history(requester.tenantId(), dataSourceId).stream()
                .anyMatch(r -> r.registration().id().equals(registrationId));
        if (!ofSource) {
            throw new TenancyException(TenancyException.Reason.NOT_FOUND, "no such registration for the source");
        }
    }

    /** The permission is given to persons only; the service checks it again on every change. */
    private static void requirePerson(Requester requester) {
        if (requester.type() != Requester.PrincipalType.HUMAN) {
            throw new TenancyException(TenancyException.Reason.NOT_A_PERSON, "tenancy is managed by persons");
        }
    }
}
