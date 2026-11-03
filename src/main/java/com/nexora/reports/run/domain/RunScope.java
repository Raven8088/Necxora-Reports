package com.nexora.reports.run.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who may read a run and its artifact (D-A4-6, D-B8-7): the same tenant, the same source and the same owner. Every
 * read is filtered by the three; any other run is indistinguishable from one that does not exist.
 */
public record RunScope(UUID tenantId, UUID dataSourceId, String ownerSubject) {

    public RunScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(ownerSubject, "ownerSubject");
        if (ownerSubject.isBlank()) {
            throw new IllegalArgumentException("blank owner subject");
        }
    }
}
