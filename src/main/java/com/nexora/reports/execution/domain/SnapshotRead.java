package com.nexora.reports.execution.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Result of one {@code /2} execution: every dataset read in the same REPEATABLE READ, read-only transaction, whose
 * snapshot is recorded as PostgreSQL reports it ({@code pg_current_snapshot()}) as evidence of consistency.
 */
public record SnapshotRead(String snapshot, List<DatasetRows> datasets, Duration duration) {

    public SnapshotRead {
        Objects.requireNonNull(snapshot, "snapshot");
        datasets = List.copyOf(datasets);
        Objects.requireNonNull(duration, "duration");
    }

    public DatasetRows dataset(String datasetId) {
        return datasets.stream().filter(d -> d.datasetId().equals(datasetId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset"));
    }

    public boolean truncated() {
        return datasets.stream().anyMatch(DatasetRows::truncated);
    }
}
