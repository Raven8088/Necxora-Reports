package com.nexora.reports.run.domain;

import java.util.Objects;
import java.util.UUID;

/** A run as the code that started it refers to it: its id with the tenant and source it belongs to. */
public record RunRef(UUID runId, UUID tenantId, UUID dataSourceId) {

    public RunRef {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
    }
}
