package com.nexora.reports.config;

import com.nexora.reports.execution.domain.ExecutionLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Server-side execution limits ({@code nexora.reports.execution.*}). */
@ConfigurationProperties("nexora.reports.execution")
public record ExecutionProperties(
        Integer defaultMaxRows,
        Integer maxRowsCeiling,
        Duration statementTimeout,
        Duration lockTimeout,
        Duration maxDuration,
        Integer fetchSize,
        Duration acquireTimeout,
        Integer maxConcurrentExecutions) {

    public ExecutionLimits limits() {
        return new ExecutionLimits(
                defaultMaxRows == null ? 1000 : defaultMaxRows,
                maxRowsCeiling == null ? 10_000 : maxRowsCeiling,
                statementTimeout == null ? Duration.ofSeconds(30) : statementTimeout,
                lockTimeout == null ? Duration.ofSeconds(2) : lockTimeout,
                maxDuration == null ? Duration.ofSeconds(60) : maxDuration,
                fetchSize == null ? 500 : fetchSize,
                acquireTimeout == null ? Duration.ofSeconds(2) : acquireTimeout);
    }

    public int concurrency() {
        return maxConcurrentExecutions == null ? 16 : maxConcurrentExecutions;
    }
}
