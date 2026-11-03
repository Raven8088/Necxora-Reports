package com.nexora.reports.result.domain;

import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything one report is assembled from: the validated documents (B2), the presentation of the same
 * specification ({@link SpecPresentation}), the translated queries (B3), the rows of the single snapshot (B4) and
 * what only the run knows. Optional data that is absent becomes {@link Fact.Unavailable}.
 *
 * @param sourceName      the data source's name in Semantic, if the run has it
 * @param excludedColumns a description of the columns excluded by the policy (counts by reason), if provided
 */
public record AssemblyInput(UUID runId, ValidatedReportPlan plan, SpecPresentation spec, List<SqlQueryV2> queries,
                            SnapshotRead read, Instant startedAt, Instant finishedAt, Instant generatedAt,
                            String reportsVersion, Optional<String> sourceName, Optional<String> excludedColumns,
                            AssemblyLimits limits) {

    public AssemblyInput {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(spec, "spec");
        queries = List.copyOf(queries);
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(reportsVersion, "reportsVersion");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(excludedColumns, "excludedColumns");
        Objects.requireNonNull(limits, "limits");
    }
}
