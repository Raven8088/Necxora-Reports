package com.nexora.reports.execution.domain;

import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rows of one dataset read by the {@code /2} executor, in query order, with typed values ({@link OutputValueType}).
 * {@code truncated} means the source had more rows than {@code maxRows}; the extra row read to detect it is not
 * kept. Values may be {@code null}.
 */
public record DatasetRows(String datasetId, List<SqlQueryV2.Output> columns, List<List<Object>> rows,
                          boolean truncated, Duration duration) {

    public DatasetRows {
        Objects.requireNonNull(datasetId, "datasetId");
        columns = List.copyOf(columns);
        List<List<Object>> copy = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("row width does not match the columns");
            }
            copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(copy);
        Objects.requireNonNull(duration, "duration");
    }

    /** Never prints values: they are business data. */
    @Override
    public String toString() {
        return "DatasetRows[" + datasetId + ", " + rows.size() + " rows" + (truncated ? ", truncated" : "") + "]";
    }
}
