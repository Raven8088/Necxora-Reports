package com.nexora.reports.translation.domain;

import java.util.List;
import java.util.Objects;

/**
 * The single, parameterized SELECT produced for a validated plan. The text contains only identifiers taken from the
 * plan's natural keys (quoted), fixed keywords and {@code ?} placeholders; every value is in {@link #parameters()}.
 * The last parameter is the row limit ({@code maxRows + 1}, to detect truncation).
 */
public record SqlQuery(String sql, List<SqlParameter> parameters, List<ResultColumn> columns, int maxRows,
                       SchemaExpectation schema) {

    public SqlQuery {
        Objects.requireNonNull(sql, "sql");
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
        Objects.requireNonNull(schema, "schema");
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
    }

    /** Never prints the parameter values. */
    @Override
    public String toString() {
        return "SqlQuery[" + columns.size() + " columns, " + parameters.size() + " parameters]";
    }
}
