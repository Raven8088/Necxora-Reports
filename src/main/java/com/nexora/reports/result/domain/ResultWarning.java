package com.nexora.reports.result.domain;

import java.util.Objects;

/**
 * A warning of the report (EX-03 and the cell text limit). Codes are stable ({@code dataset_truncated},
 * {@code cell_text_truncated}); the presentation writes the message in the report's language.
 *
 * @param output    the column concerned, or {@code null}
 * @param delivered rows delivered (truncation) or cells cut (text limit)
 * @param limit     the row limit (truncation) or the text limit in UTF-16 units
 */
public record ResultWarning(String code, String datasetId, String output, long delivered, long limit) {

    public static final String DATASET_TRUNCATED = "dataset_truncated";
    public static final String CELL_TEXT_TRUNCATED = "cell_text_truncated";

    public ResultWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(datasetId, "datasetId");
    }
}
