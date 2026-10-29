package com.nexora.reports.execution.domain;

import java.util.Collections;
import java.util.List;

/**
 * Rows read from PostgreSQL, already converted to JSON-friendly values ({@code null}, {@code String},
 * {@code Long} or {@code Boolean}) in the order of the query's result columns.
 *
 * @param truncated more rows existed than the limit allowed; only the first {@code maxRows} are kept
 */
public record QueryResult(List<List<Object>> rows, boolean truncated) {

    public QueryResult {
        rows = rows.stream().map(r -> Collections.unmodifiableList(new java.util.ArrayList<>(r))).toList();
    }
}
