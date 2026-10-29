package com.nexora.reports.execution.application.port;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.QueryResult;
import com.nexora.reports.translation.domain.SqlQuery;

/**
 * Runs one translated query in a read-only transaction, after checking the live schema against
 * {@link SqlQuery#schema()}. Throws {@link com.nexora.reports.execution.domain.ExecutionException} with
 * {@code PHYSICAL_SCHEMA_MISMATCH}, {@code DATA_SOURCE_UNAVAILABLE}, {@code EXECUTION_TIMEOUT},
 * {@code TOO_MANY_EXECUTIONS} or {@code EXECUTION_FAILED}.
 */
public interface ReadOnlyExecutor {

    QueryResult execute(DataSourceCredentials credentials, SqlQuery query, ExecutionLimits limits);
}
