package com.nexora.reports.api;

import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.plan2.domain.ReportsCapabilities;
import com.nexora.reports.security.Permission;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * A4-B10 (D-A4-8, D-B10-3): {@code GET /api/v1/capabilities}, the {@code nexora-capabilities/1} of Reports. Readable
 * by every authenticated principal of a tenant (person, service or the technical identity of Intelligence), with no
 * specific permission and never anonymously: the token is validated exactly as for every other endpoint, and a
 * token without a valid tenant is rejected before reaching here. It discloses no data, no data source and no tenant;
 * reading it grants nothing else (an AI principal still cannot execute anything).
 */
@RestController
public class CapabilitiesController {

    public static final String CAPABILITIES = "/api/v1/capabilities";

    private final ReportsCapabilities capabilities;

    public CapabilitiesController(ReportsCapabilities capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** {@code requester} is resolved from the token only to require a tenant-scoped Reports principal. */
    @GetMapping(path = CAPABILITIES, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_TENANT_PRINCIPAL)
    public ResponseEntity<Map<String, Object>> capabilities(Requester requester) {
        Objects.requireNonNull(requester.tenantId(), "tenant");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(capabilities.document());
    }
}
