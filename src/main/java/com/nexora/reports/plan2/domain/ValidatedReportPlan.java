package com.nexora.reports.plan2.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of {@link PlanV2Validator}: a report plan whose documents have all been checked. Datasets keep the order of
 * the report plan. {@code maxRows} is the effective row limit of each dataset: the minimum of the plan's
 * {@code LIMIT}, the report plan's budget and the ceiling of Reports.
 */
public record ValidatedReportPlan(ReportPlanV1 reportPlan, ReportSpecView spec, List<Dataset> datasets) {

    public ValidatedReportPlan {
        Objects.requireNonNull(reportPlan, "reportPlan");
        Objects.requireNonNull(spec, "spec");
        datasets = List.copyOf(datasets);
    }

    public record Dataset(String datasetId, UUID planId, LogicalPlanV2 plan, int maxRows) {
    }
}
