package com.nexora.reports.result.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity and traceability of one run (EX-15, {@code nexora-report-run/1}): fingerprints, metadata snapshot,
 * consistency of the read (one REPEATABLE READ, read-only transaction and its {@code pg_current_snapshot()}) and
 * times.
 */
public record RunMetadata(UUID runId, UUID tenantId, UUID dataSourceId, String reportPlanFingerprint,
                          String specFingerprint, UUID metadataSnapshotId, String isolation, boolean readOnly,
                          int transactionCount, String snapshot, Instant startedAt, Instant finishedAt,
                          Duration readDuration) {

    public RunMetadata {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(reportPlanFingerprint, "reportPlanFingerprint");
        Objects.requireNonNull(specFingerprint, "specFingerprint");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(readDuration, "readDuration");
    }
}
