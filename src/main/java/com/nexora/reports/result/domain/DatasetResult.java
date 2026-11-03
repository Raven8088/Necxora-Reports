package com.nexora.reports.result.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The rows of one dataset with what they mean. Immutable: only {@link ResultAssembler} builds it, from the
 * unmodifiable rows of the executor, without copying them (a row is rebuilt only when one of its texts is cut).
 * Values keep their exact types ({@code Long}, {@code BigDecimal}, {@code String}, {@code Boolean},
 * {@code LocalDate}, {@code LocalDateTime}, {@code OffsetDateTime} in UTC) and may be {@code null}.
 */
public final class DatasetResult {

    private final String datasetId;
    private final UUID planId;
    private final String planFingerprint;
    private final String title;
    private final String mode;
    private final List<ResultColumn2> columns;
    private final List<List<Object>> rows;
    private final boolean truncated;
    private final int maxRows;
    private final Duration duration;

    DatasetResult(String datasetId, UUID planId, String planFingerprint, String title, String mode,
                  List<ResultColumn2> columns, List<List<Object>> unmodifiableRows, boolean truncated, int maxRows,
                  Duration duration) {
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.planFingerprint = Objects.requireNonNull(planFingerprint, "planFingerprint");
        this.title = title;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.columns = List.copyOf(columns);
        this.rows = Objects.requireNonNull(unmodifiableRows, "rows");
        this.truncated = truncated;
        this.maxRows = maxRows;
        this.duration = Objects.requireNonNull(duration, "duration");
    }

    public String datasetId() {
        return datasetId;
    }

    public UUID planId() {
        return planId;
    }

    public String planFingerprint() {
        return planFingerprint;
    }

    /** The dataset's title in the specification, or {@code null} if it has none. */
    public String title() {
        return title;
    }

    /** {@code DETAIL} or {@code AGGREGATE}. */
    public String mode() {
        return mode;
    }

    /** Every column, hidden ones included, in the plan's output order. */
    public List<ResultColumn2> columns() {
        return columns;
    }

    /** The columns a presentation shows: not hidden, in the plan's output order. */
    public List<ResultColumn2> visibleColumns() {
        return columns.stream().filter(c -> !c.hidden()).toList();
    }

    public ResultColumn2 column(String output) {
        return columns.stream().filter(c -> c.output().equals(output)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown output"));
    }

    /** Unmodifiable rows in the plan's order (EX-07); each row has one value per column. */
    public List<List<Object>> rows() {
        return rows;
    }

    public int rowCount() {
        return rows.size();
    }

    public boolean truncated() {
        return truncated;
    }

    public int maxRows() {
        return maxRows;
    }

    public Duration duration() {
        return duration;
    }

    /** Value equality (EX-18: the same input gives an equal result). */
    @Override
    public boolean equals(Object o) {
        return o instanceof DatasetResult d && datasetId.equals(d.datasetId) && planId.equals(d.planId)
                && planFingerprint.equals(d.planFingerprint) && Objects.equals(title, d.title)
                && mode.equals(d.mode) && columns.equals(d.columns) && rows.equals(d.rows)
                && truncated == d.truncated && maxRows == d.maxRows && duration.equals(d.duration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasetId, planId, planFingerprint, title, mode, columns, rows, truncated, maxRows,
                duration);
    }

    /** Never prints values: they are business data. */
    @Override
    public String toString() {
        return "DatasetResult[" + datasetId + ", " + rows.size() + " rows" + (truncated ? ", truncated" : "") + "]";
    }
}
