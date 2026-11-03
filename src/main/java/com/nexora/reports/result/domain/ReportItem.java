package com.nexora.reports.result.domain;

import com.nexora.reports.execution.domain.OutputValueType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An element of a section, in the specification's order (EX-04). Only the two elements of {@code basic-a}. */
public sealed interface ReportItem {

    String datasetId();

    /** A detail table (EX-05, EX-07). */
    record Table(String datasetId) implements ReportItem {
        public Table {
            Objects.requireNonNull(datasetId, "datasetId");
        }
    }

    /**
     * A summary by groups (EX-08) with, optionally, the general total taken from its own dataset (EX-09), never
     * computed from the summary.
     */
    record SummaryTable(String datasetId, Optional<Total> total) implements ReportItem {
        public SummaryTable {
            Objects.requireNonNull(datasetId, "datasetId");
            Objects.requireNonNull(total, "total");
        }
    }

    /**
     * The total row of a summary: one cell per measure of the total dataset, paired with the summary's measure of
     * the same output id and value type (D-A4-7). Summary measures without a pair have no total cell.
     */
    record Total(String datasetId, List<TotalCell> cells) {
        public Total {
            Objects.requireNonNull(datasetId, "datasetId");
            cells = List.copyOf(cells);
        }

        public Optional<TotalCell> cell(String output) {
            return cells.stream().filter(c -> c.output().equals(output)).findFirst();
        }
    }

    /** @param value the value of the total dataset's single row, as read; may be {@code null} */
    record TotalCell(String output, OutputValueType valueType, Object value) {
        public TotalCell {
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(valueType, "valueType");
        }
    }
}
