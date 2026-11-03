package com.nexora.reports.result.domain;

import java.util.List;
import java.util.Objects;

/**
 * A4-B6: the typed, format-independent result of one report run (A4 proposal v3, section 4): what to present, never
 * how. A renderer (B7: Excel; later others) walks it without reading any contract again. Immutable. Not to be
 * confused with R1's {@code execution.domain.ReportResult}, which is the result of one {@code /1} plan.
 *
 * @param presentationCells the conservative cell count of {@link PresentationBudget}, already within the limit
 */
public record ReportResult(RunMetadata run, ReportHeader header, ResultStatus status, List<DatasetResult> datasets,
                           List<ReportSection> sections, Methodology methodology, List<ResultWarning> warnings,
                           long presentationCells) {

    public ReportResult {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(status, "status");
        datasets = List.copyOf(datasets);
        sections = List.copyOf(sections);
        Objects.requireNonNull(methodology, "methodology");
        warnings = List.copyOf(warnings);
    }

    public DatasetResult dataset(String datasetId) {
        return datasets.stream().filter(d -> d.datasetId().equals(datasetId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset"));
    }
}
