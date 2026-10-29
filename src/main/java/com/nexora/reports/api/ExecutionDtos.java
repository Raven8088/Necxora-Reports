package com.nexora.reports.api;

import com.nexora.reports.execution.domain.ExecutionWarning;
import com.nexora.reports.execution.domain.ReportResult;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.translation.domain.ResultColumn;

import java.util.List;
import java.util.UUID;

/** HTTP contract of executions (R1). */
public final class ExecutionDtos {

    private ExecutionDtos() {
    }

    /**
     * Optional request body. It never contains SQL, plan documents, tenants or connection details: unknown properties
     * are rejected with 400.
     *
     * @param maxRows rows to return at most (1..server ceiling); the server default when absent
     */
    public record ExecutionRequest(Integer maxRows) {
    }

    public record ColumnResponse(String name, ResultColumn.Role role, ResultColumn.OutputType type) {
    }

    public record WarningResponse(ExecutionWarning.Code code, String message) {
    }

    public record ExecutionResponse(UUID executionId, UUID planId, UUID dataSourceId, String planFingerprint,
                                    PlanValidity.Status validity, List<ColumnResponse> columns,
                                    List<List<Object>> rows, int rowCount, boolean truncated, int maxRows,
                                    long durationMs, List<WarningResponse> warnings) {

        static ExecutionResponse from(ReportResult r) {
            return new ExecutionResponse(r.executionId(), r.planId(), r.dataSourceId(), r.planFingerprint(),
                    r.validity(),
                    r.columns().stream().map(c -> new ColumnResponse(c.name(), c.role(), c.type())).toList(),
                    r.rows(), r.rowCount(), r.truncated(), r.maxRows(), r.durationMillis(),
                    r.warnings().stream().map(w -> new WarningResponse(w.code(), w.message())).toList());
        }
    }
}
