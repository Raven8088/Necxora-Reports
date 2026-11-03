package com.nexora.reports.plan2.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A validated {@code nexora-report-plan/1}: the datasets of one report, read in a single snapshot. */
public record ReportPlanV1(UUID tenantId, UUID dataSourceId, String profile, String specFingerprint,
                           List<PlanRef> plans, Budget budget, String fingerprint) {

    public static final String FORMAT = "nexora-report-plan/1";

    public ReportPlanV1 {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        plans = List.copyOf(plans);
        Objects.requireNonNull(budget, "budget");
    }

    public record PlanRef(String datasetId, UUID planId, String planFingerprint) {
    }

    public record Budget(int maxPlans, int maxRowsPerPlan, int maxTotalRows, long statementTimeoutMs,
                         long maxDurationMs) {
    }
}
