package com.nexora.reports.result.domain;

import java.util.List;
import java.util.Objects;

/** A section of the report with its elements, in the specification's order (EX-04). */
public record ReportSection(String id, String title, List<ReportItem> items) {

    public ReportSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        items = List.copyOf(items);
    }
}
