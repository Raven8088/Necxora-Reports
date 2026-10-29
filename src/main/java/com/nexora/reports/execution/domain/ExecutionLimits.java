package com.nexora.reports.execution.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-side limits of every execution; a request can only ask for fewer rows, never for more time or rows.
 *
 * @param defaultMaxRows   rows returned when the request does not ask for a limit
 * @param maxRowsCeiling   absolute maximum of rows a request may ask for
 * @param statementTimeout PostgreSQL {@code statement_timeout} of the transaction
 * @param lockTimeout      PostgreSQL {@code lock_timeout} of the transaction
 * @param maxDuration      wall-clock budget of the database work (checked while reading rows)
 * @param fetchSize        JDBC fetch size (rows are streamed with a server-side cursor)
 * @param acquireTimeout   how long to wait for an execution slot before refusing with "too many executions"
 */
public record ExecutionLimits(int defaultMaxRows, int maxRowsCeiling, Duration statementTimeout,
                              Duration lockTimeout, Duration maxDuration, int fetchSize, Duration acquireTimeout) {

    public ExecutionLimits {
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(maxDuration, "maxDuration");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (defaultMaxRows < 1 || maxRowsCeiling < defaultMaxRows || fetchSize < 1
                || statementTimeout.isZero() || statementTimeout.isNegative()
                || lockTimeout.isZero() || lockTimeout.isNegative()
                || maxDuration.isZero() || maxDuration.isNegative() || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid execution limits");
        }
    }

    /** The effective row limit for a request ({@code null} means the default). */
    public int effectiveMaxRows(Integer requested) {
        if (requested == null) {
            return defaultMaxRows;
        }
        if (requested < 1 || requested > maxRowsCeiling) {
            throw new IllegalArgumentException("maxRows out of range");
        }
        return requested;
    }
}
