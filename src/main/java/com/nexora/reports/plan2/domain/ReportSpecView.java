package com.nexora.reports.plan2.domain;

import java.util.List;

/**
 * What Reports keeps of a validated {@code nexora-report-spec/1} after B2: its profile, status, datasets and
 * fingerprint. The whole document has been validated (schema and SPEC-R1…SPEC-R6); the presentation view (title,
 * sections, labels) is built in a later block of A4.
 */
public record ReportSpecView(String profile, String status, List<String> datasetIds, String fingerprint) {

    public static final String FORMAT = "nexora-report-spec/1";

    public ReportSpecView {
        datasetIds = List.copyOf(datasetIds);
    }
}
