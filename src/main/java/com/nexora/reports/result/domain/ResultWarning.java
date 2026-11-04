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
    /**
     * EX-TLS-1: the run read the source under a transport exception: neither the encryption of the connection nor
     * the identity of the server were verified. A run-level warning (no dataset); it is added to the outcome after
     * the presentation, so it never changes the Excel file (contract {@code nexora-excel-report/1} unchanged).
     */
    public static final String TRANSPORT_NOT_VERIFIED = "transport_not_verified";

    public ResultWarning {
        Objects.requireNonNull(code, "code");
        if (!TRANSPORT_NOT_VERIFIED.equals(code)) {
            Objects.requireNonNull(datasetId, "datasetId");
        } else if (datasetId != null) {
            throw new IllegalArgumentException("transport_not_verified concerns the run, not a dataset");
        }
    }

    /** The run-level warning of a run made under a transport exception (EX-TLS-1). */
    public static ResultWarning transportNotVerified() {
        return new ResultWarning(TRANSPORT_NOT_VERIFIED, null, null, 0, 0);
    }
}
