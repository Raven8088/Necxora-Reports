package com.nexora.reports.result.domain;

import java.util.List;

/**
 * Conservative count of the cells a presentation of the report will write (precision 5 of A4-B6), checked against
 * {@link AssemblyLimits#maxCells()} before the report is accepted, so the renderer (B7) never has to refuse later:
 * <ul>
 *   <li>every item: (rows + header row + title row + total row if any) × visible columns, plus one cell for the
 *       place where a detail table is cited in its section (D-A4-4: each detail on its own sheet);</li>
 *   <li>every section: one title cell;</li>
 *   <li>header block (EX-02, EX-03): two columns × ({@value #HEADER_FIXED_ROWS} fixed rows + one per filter and per
 *       warning);</li>
 *   <li>methodology (EX-14 to EX-16): four columns × ({@value #METHODOLOGY_FIXED_ROWS} fixed rows + one per relation,
 *       relationship, term, definition, order rule and {@value #ROWS_PER_DATASET} per dataset).</li>
 * </ul>
 * Hidden columns are not counted, because they are never written.
 */
public final class PresentationBudget {

    static final int HEADER_FIXED_ROWS = 16;
    static final int METHODOLOGY_FIXED_ROWS = 24;
    static final int ROWS_PER_DATASET = 8;

    private PresentationBudget() {
    }

    public static long cells(List<ReportSection> sections, List<DatasetResult> datasets, int filters, int warnings,
                             Methodology methodology) {
        long cells = 0;
        for (ReportSection section : sections) {
            cells = Math.addExact(cells, 1);
            for (ReportItem item : section.items()) {
                DatasetResult dataset = find(datasets, item.datasetId());
                long width = Math.max(1, dataset.visibleColumns().size());
                long rows = dataset.rowCount() + 2L;
                if (item instanceof ReportItem.SummaryTable s && s.total().isPresent()) {
                    rows++;
                }
                cells = Math.addExact(cells, Math.multiplyExact(rows, width));
                if (item instanceof ReportItem.Table) {
                    cells = Math.addExact(cells, 1);
                }
            }
        }
        cells = Math.addExact(cells, 2L * (HEADER_FIXED_ROWS + filters + warnings));
        long methodologyRows = METHODOLOGY_FIXED_ROWS + methodology.relations().size()
                + methodology.relationships().size() + methodology.terms().size()
                + methodology.definitions().size() + methodology.orderRules().size()
                + (long) ROWS_PER_DATASET * methodology.datasets().size();
        return Math.addExact(cells, 4L * methodologyRows);
    }

    private static DatasetResult find(List<DatasetResult> datasets, String id) {
        return datasets.stream().filter(d -> d.datasetId().equals(id)).findFirst().orElseThrow();
    }
}
