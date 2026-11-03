package com.nexora.reports.tenancy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One immutable version of the tenancy registration of a (tenant, data source): the declared model, the identity
 * and the exact set of schemas captured from the database, who registered it and when, and the SHA-256 fingerprint
 * of all of that ({@link TenancyRules#fingerprint}). A change produces a new version; versions are never edited.
 *
 * @param schemas the non-system schemas on which the read-only role has effective USAGE, sorted and distinct
 */
public record TenancyRegistration(UUID id, UUID tenantId, UUID dataSourceId, int version, TenancyModel model,
                                  DatabaseIdentity identity, List<String> schemas, String registeredBy,
                                  Instant registeredAt, String fingerprint) {

    public TenancyRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(registeredBy, "registeredBy");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(fingerprint, "fingerprint");
        schemas = List.copyOf(new TreeSet<>(schemas));
        if (version < 1 || schemas.isEmpty()) {
            throw new IllegalArgumentException("invalid tenancy registration");
        }
    }
}
