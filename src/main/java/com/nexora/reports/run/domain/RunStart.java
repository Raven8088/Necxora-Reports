package com.nexora.reports.run.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The start of a run, recorded before any data is read so that every started run stays traceable. Without an outcome
 * by {@code started + maxDuration} it is INTERRUPTED.
 */
public record RunStart(UUID runId, UUID tenantId, UUID dataSourceId, UUID reportPlanId, RunOwner owner,
                       Duration maxDuration) {

    public static final Duration MIN_DURATION = Duration.ofSeconds(1);
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    public RunStart {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(reportPlanId, "reportPlanId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.compareTo(MIN_DURATION) < 0 || maxDuration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("maxDuration out of range");
        }
    }

    public RunRef ref() {
        return new RunRef(runId, tenantId, dataSourceId);
    }
}
