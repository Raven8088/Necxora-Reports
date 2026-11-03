package com.nexora.reports.execution.domain;

import com.nexora.reports.plan2.domain.ReportPlanV1;

import java.time.Duration;
import java.util.Objects;

/**
 * Effective limits of one {@code /2} execution (A4-B4): every value is the minimum of what the report plan asks for
 * and what the server allows, so a plan can only ask for less, never for more.
 *
 * @param statementTimeout  PostgreSQL {@code statement_timeout} of each query (also capped by the remaining time)
 * @param lockTimeout       PostgreSQL {@code lock_timeout} of the transaction
 * @param maxDuration       wall-clock budget of the whole execution: all checks and all datasets
 * @param fetchSize         JDBC fetch size (rows are streamed with a server-side cursor)
 * @param acquireTimeout    how long to wait for an execution slot
 * @param maxTotalRows      rows kept in memory across all datasets
 * @param maxEstimatedBytes estimated memory of the rows kept across all datasets ({@link ResultSizeEstimate})
 */
public record RunBudget(Duration statementTimeout, Duration lockTimeout, Duration maxDuration, int fetchSize,
                        Duration acquireTimeout, int maxTotalRows, long maxEstimatedBytes) {

    /** Initial memory budget of the rows of one execution (A4 proposal v3, P-2: 64 MiB). */
    public static final long DEFAULT_MAX_ESTIMATED_BYTES = 64L * 1024 * 1024;

    public RunBudget {
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(maxDuration, "maxDuration");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (!positive(statementTimeout) || !positive(lockTimeout) || !positive(maxDuration)
                || acquireTimeout.isNegative() || fetchSize < 1 || maxTotalRows < 1 || maxEstimatedBytes < 1) {
            throw new IllegalArgumentException("invalid run budget");
        }
    }

    /** The budget of a validated report plan under the server limits. */
    public static RunBudget of(ReportPlanV1.Budget plan, ExecutionLimits server, long maxEstimatedBytes) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(server, "server");
        if (plan.statementTimeoutMs() < 1 || plan.maxDurationMs() < 1 || plan.maxTotalRows() < 1) {
            throw new IllegalArgumentException("invalid report plan budget");
        }
        return new RunBudget(min(Duration.ofMillis(plan.statementTimeoutMs()), server.statementTimeout()),
                server.lockTimeout(), min(Duration.ofMillis(plan.maxDurationMs()), server.maxDuration()),
                server.fetchSize(), server.acquireTimeout(), plan.maxTotalRows(), maxEstimatedBytes);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static boolean positive(Duration d) {
        return !d.isZero() && !d.isNegative();
    }
}
