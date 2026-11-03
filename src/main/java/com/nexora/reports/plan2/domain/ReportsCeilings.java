package com.nexora.reports.plan2.domain;

/**
 * Upper limits of Reports for a report plan (decision P-2: initial values, configurable). A report plan whose budget
 * or plans exceed them is refused before any connection is opened. Wiring to the application configuration belongs
 * to a later block of A4.
 */
public record ReportsCeilings(int maxPlans, int maxRowsPerPlan, int maxTotalRows, int maxColumnsPerPlan,
                              long maxStatementTimeoutMs, long maxDurationMs) {

    /** Initial values of the A4 proposal v3 (section 4 bis, point 5) and of the basic-a budget of Semantic. */
    public static final ReportsCeilings DEFAULTS = new ReportsCeilings(3, 10_000, 30_000, 64, 30_000, 60_000);

    public ReportsCeilings {
        if (maxPlans < 1 || maxRowsPerPlan < 1 || maxTotalRows < 1 || maxColumnsPerPlan < 1
                || maxStatementTimeoutMs < 1 || maxDurationMs < 1) {
            throw new IllegalArgumentException("ceilings must be positive");
        }
    }
}
