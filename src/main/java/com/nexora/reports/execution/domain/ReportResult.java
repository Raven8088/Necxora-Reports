package com.nexora.reports.execution.domain;

import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.translation.domain.ResultColumn;

import java.util.List;
import java.util.UUID;

/** Outcome of a successful execution, as returned to the client. */
public record ReportResult(
        UUID executionId,
        UUID planId,
        UUID dataSourceId,
        String planFingerprint,
        PlanValidity.Status validity,
        List<ResultColumn> columns,
        List<List<Object>> rows,
        boolean truncated,
        int maxRows,
        long durationMillis,
        List<ExecutionWarning> warnings) {

    public ReportResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    public int rowCount() {
        return rows.size();
    }
}
