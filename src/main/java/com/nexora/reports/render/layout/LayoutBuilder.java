package com.nexora.reports.render.layout;

import com.nexora.reports.execution.domain.OutputValueType;
import com.nexora.reports.result.domain.DatasetResult;
import com.nexora.reports.result.domain.Fact;
import com.nexora.reports.result.domain.FilterDescription;
import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ReportHeader;
import com.nexora.reports.result.domain.ReportItem;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ReportSection;
import com.nexora.reports.result.domain.ResultColumn2;
import com.nexora.reports.result.domain.ResultStatus;
import com.nexora.reports.result.domain.ResultWarning;
import com.nexora.reports.result.domain.SourceObject;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.CellText;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A4-B7: the layout of {@code nexora-excel-report/1} for any {@link ReportResult} (D-B7-1, D-B7-2). It knows the
 * generic result of A4-B6 only (sections, items, typed columns and rows, header, methodology, warnings), never a
 * particular report, plan or database, and it does not know Apache POI: {@code render.xlsx} writes what it decides.
 * <p>
 * Sheets (EX-01): «Reporte», one sheet per detail table in order of appearance (D-A4-4), «Metodología». «Reporte»
 * holds the header (EX-02), the warnings (EX-03) and the sections in order (EX-04): a summary as title, header,
 * one row per group (EX-08) and «Total general» from its own dataset (EX-09); a detail table as a citation of its
 * sheet. A detail sheet has the header row frozen and an autofilter (EX-13). Hidden columns are never written
 * (EX-05, EX-12); values follow {@link ValueFormat}. Every text from outside, data, labels, titles, filters and
 * methodology alike, goes through {@link CellValue#text}, which keeps formula triggers literal (EX-11).
 */
public final class LayoutBuilder {

    /** Excel's maximum characters in a cell. */
    public static final int EXCEL_MAX_TEXT = AssemblyLimits.EXCEL_MAX_CELL_TEXT;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReportResult result;
    private final ReportHeader header;
    private final String lang;
    private final ValueFormat values;
    private final Map<String, String> detailSheets = new LinkedHashMap<>();
    /** «dataset» «column» → values written as text to keep their precision (R-3), in order of appearance. */
    private final Map<String, Long> asText = new LinkedHashMap<>();

    private LayoutBuilder(ReportResult result) {
        this.result = result;
        this.header = result.header();
        this.lang = header.labelLanguage();
        this.values = new ValueFormat(header.timeZone(), lang);
        SheetNames names = new SheetNames(Label.SHEET_REPORT.in(lang), Label.SHEET_METHODOLOGY.in(lang));
        for (ReportSection section : result.sections()) {
            for (ReportItem item : section.items()) {
                if (item instanceof ReportItem.Table t && !detailSheets.containsKey(t.datasetId())) {
                    DatasetResult d = result.dataset(t.datasetId());
                    detailSheets.put(t.datasetId(), names.unique(d.title(), d.datasetId()));
                }
            }
        }
        countValuesAsText();
    }

    public static WorkbookLayout build(ReportResult result) {
        return new LayoutBuilder(result).layout();
    }

    private WorkbookLayout layout() {
        List<SheetLayout> sheets = new ArrayList<>();
        sheets.add(new SheetLayout(Label.SHEET_REPORT.in(lang), reportSheet(), 0, null));
        detailSheets.forEach((datasetId, name) -> sheets.add(detailSheet(result.dataset(datasetId), name)));
        sheets.add(new SheetLayout(Label.SHEET_METHODOLOGY.in(lang), methodologySheet(), 0, null));
        return new WorkbookLayout(header.title(), sheets);
    }

    // ------------------------------------------------------------------ «Reporte»

    private RowStream reportSheet() {
        RowStream rows = new RowStream();
        rows.add(LayoutRow.of(text(0, header.title(), StyleKind.TITLE)));
        rows.add(LayoutRow.EMPTY);
        pair(rows, Label.TITLE, header.title());
        pair(rows, Label.REQUEST, header.requestText() == null ? "" : header.requestText());
        pair(rows, Label.SOURCE, withId(header.sourceName(), header.dataSourceId().toString()));
        pair(rows, Label.TENANT, withId(header.tenantName(), header.tenantId().toString()));
        pair(rows, Label.GENERATED, when(header.generatedAt()));
        if (header.filters().isEmpty()) {
            pair(rows, Label.FILTERS, Label.NO_FILTERS.in(lang));
        } else {
            for (int i = 0; i < header.filters().size(); i++) {
                String phrase = filter(header.filters().get(i));
                rows.add(i == 0 ? LayoutRow.of(label(0, Label.FILTERS), text(1, phrase, StyleKind.VALUE))
                        : LayoutRow.of(text(1, phrase, StyleKind.VALUE)));
            }
        }
        pair(rows, Label.PERIOD, Label.NOT_APPLICABLE.in(lang));
        pair(rows, Label.VERSION, header.reportsVersion() == null ? "" : header.reportsVersion());
        pair(rows, Label.STATUS, (result.status() == ResultStatus.PARTIAL ? Label.STATUS_PARTIAL
                : Label.STATUS_COMPLETED).in(lang));
        List<String> warnings = new ArrayList<>();
        for (ResultWarning w : result.warnings()) {
            warnings.add(warning(w));
        }
        if (!asText.isEmpty()) {
            long total = asText.values().stream().mapToLong(Long::longValue).sum();
            String where = asText.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
                    .collect(Collectors.joining("; "));
            warnings.add(Label.WARNING_AS_TEXT.format(lang, total, where));
        }
        for (String w : warnings) {
            rows.add(LayoutRow.of(label(0, Label.WARNING, StyleKind.WARNING), text(1, w, StyleKind.WARNING)));
        }
        for (ReportSection section : result.sections()) {
            rows.add(LayoutRow.EMPTY);
            rows.add(LayoutRow.of(text(0, orElse(section.title(), section.id()), StyleKind.SECTION_TITLE)));
            for (ReportItem item : section.items()) {
                DatasetResult d = result.dataset(item.datasetId());
                switch (item) {
                    case ReportItem.SummaryTable s -> summary(rows, d, s.total());
                    case ReportItem.Table t -> rows.add(LayoutRow.of(text(0, Label.SEE_SHEET.format(lang,
                            detailSheets.get(t.datasetId()), d.rowCount()), StyleKind.NOTE)));
                }
            }
        }
        return rows;
    }

    private void summary(RowStream rows, DatasetResult d, Optional<ReportItem.Total> total) {
        List<ResultColumn2> columns = d.visibleColumns();
        rows.add(LayoutRow.EMPTY);
        rows.add(LayoutRow.of(text(0, orElse(d.title(), d.datasetId()), StyleKind.ITEM_TITLE)));
        rows.add(headerRow(columns));
        rows.addAll(d.rows(), row -> dataRow(d, columns, row));
        if (total.isPresent()) {
            List<LayoutCell> cells = new ArrayList<>();
            boolean labelled = false;
            for (int j = 0; j < columns.size(); j++) {
                Optional<ReportItem.TotalCell> cell = total.get().cell(columns.get(j).output());
                if (cell.isPresent()) {
                    cells.add(new LayoutCell(j, values.format(cell.get().value(), cell.get().valueType(), false)
                            .value(), StyleKind.TOTAL_VALUE));
                } else if (!labelled) {
                    cells.add(label(j, Label.TOTAL, StyleKind.TOTAL_LABEL));
                    labelled = true;
                }
            }
            rows.add(new LayoutRow(cells));
        }
    }

    // ------------------------------------------------------------------ detail sheets

    private SheetLayout detailSheet(DatasetResult d, String name) {
        List<ResultColumn2> columns = d.visibleColumns();
        RowStream rows = new RowStream();
        rows.add(headerRow(columns));
        rows.addAll(d.rows(), row -> dataRow(d, columns, row));
        SheetLayout.FilterRange filter = columns.isEmpty() ? null
                : new SheetLayout.FilterRange(0, d.rowCount(), 0, columns.size() - 1);
        return new SheetLayout(name, rows, 1, filter);
    }

    private LayoutRow headerRow(List<ResultColumn2> columns) {
        List<LayoutCell> cells = new ArrayList<>(columns.size());
        for (int j = 0; j < columns.size(); j++) {
            cells.add(text(j, columnLabel(columns.get(j)), StyleKind.TABLE_HEADER));
        }
        return new LayoutRow(cells);
    }

    /** EX-06: a TIMESTAMP_TZ column is written in the report's zone, named in its header. */
    private String columnLabel(ResultColumn2 c) {
        return c.valueType() == OutputValueType.TIMESTAMP_TZ ? c.label() + " (" + header.timeZone().getId() + ")"
                : c.label();
    }

    private LayoutRow dataRow(DatasetResult d, List<ResultColumn2> columns, List<Object> row) {
        List<LayoutCell> cells = new ArrayList<>(columns.size());
        for (int j = 0; j < columns.size(); j++) {
            ResultColumn2 c = columns.get(j);
            CellValue v = values.format(row.get(c.position() - 1), c.valueType(), groupKey(d, c)).value();
            if (v != CellValue.BLANK) {
                cells.add(new LayoutCell(j, v, StyleKind.DATA));
            }
        }
        return new LayoutRow(cells);
    }

    private static boolean groupKey(DatasetResult d, ResultColumn2 c) {
        return "AGGREGATE".equals(d.mode()) && "DIMENSION".equals(c.role());
    }

    private void countValuesAsText() {
        for (ReportSection section : result.sections()) {
            for (ReportItem item : section.items()) {
                DatasetResult d = result.dataset(item.datasetId());
                for (ResultColumn2 c : d.visibleColumns()) {
                    long n = 0;
                    for (List<Object> row : d.rows()) {
                        if (values.format(row.get(c.position() - 1), c.valueType(), false).asText()) {
                            n++;
                        }
                    }
                    if (item instanceof ReportItem.SummaryTable s && s.total().isPresent()) {
                        Optional<ReportItem.TotalCell> t = s.total().get().cell(c.output());
                        if (t.isPresent() && values.format(t.get().value(), t.get().valueType(), false).asText()) {
                            n++;
                        }
                    }
                    if (n > 0) {
                        asText.merge("«" + orElse(d.title(), d.datasetId()) + "» «" + c.label() + "»", n,
                                Long::sum);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ «Metodología» (EX-14 to EX-16)

    private RowStream methodologySheet() {
        Methodology m = result.methodology();
        RowStream rows = new RowStream();
        rows.add(LayoutRow.of(label(0, Label.M_EXECUTION, StyleKind.SECTION_TITLE)));
        pair(rows, Label.M_SPEC_FINGERPRINT, result.run().specFingerprint());
        pair(rows, Label.M_PLAN_FINGERPRINT, result.run().reportPlanFingerprint());
        pair(rows, Label.M_METADATA_SNAPSHOT, fact(m.metadataSnapshot()));
        pair(rows, Label.M_ISOLATION, Label.M_ISOLATION_VALUE.format(lang, result.run().isolation(),
                result.run().transactionCount()));
        pair(rows, Label.M_SNAPSHOT, result.run().snapshot());
        pair(rows, Label.M_STARTED, when(result.run().startedAt()));
        pair(rows, Label.M_FINISHED, when(result.run().finishedAt()));
        rows.add(LayoutRow.of(label(0, Label.M_READ_DURATION), integer(1, result.run().readDuration().toMillis())));
        rows.add(LayoutRow.EMPTY);
        rows.add(tableHeader(Label.M_DATASET, Label.M_ROWS, Label.M_TRUNCATED, Label.M_LIMIT));
        for (Methodology.DatasetExecution e : m.datasets()) {
            rows.add(LayoutRow.of(text(0, e.datasetId(), StyleKind.VALUE), integer(1, e.rowCount()),
                    text(2, (e.truncated() ? Label.YES : Label.NO).in(lang), StyleKind.VALUE),
                    integer(3, e.maxRows())));
        }
        rows.add(tableHeader(Label.M_DATASET, Label.M_PLAN, Label.M_DURATION));
        for (Methodology.DatasetExecution e : m.datasets()) {
            rows.add(LayoutRow.of(text(0, e.datasetId(), StyleKind.VALUE),
                    text(1, e.planFingerprint(), StyleKind.VALUE), integer(2, e.durationMs())));
        }

        rows.add(LayoutRow.EMPTY);
        rows.add(LayoutRow.of(label(0, Label.M_ORIGIN, StyleKind.SECTION_TITLE)));
        rows.add(tableHeader(Label.M_OBJECT, Label.M_TYPE));
        for (SourceObject o : m.relations()) {
            rows.add(LayoutRow.of(text(0, qualified(o), StyleKind.VALUE), text(1, o.type(), StyleKind.VALUE)));
        }
        rows.add(tableHeader(Label.M_RELATIONSHIP, Label.M_ORIGIN, Label.M_STATE, Label.M_APPROVED_BY));
        for (Methodology.RelationshipUse r : m.relationships()) {
            rows.add(LayoutRow.of(text(0, String.valueOf(r.id()), StyleKind.VALUE),
                    text(1, orElse(r.origin(), ""), StyleKind.VALUE), text(2, orElse(r.status(), ""), StyleKind.VALUE),
                    text(3, fact(r.approvedBy()), StyleKind.VALUE)));
        }
        rows.add(tableHeader(Label.M_TERM, Label.M_RESOLUTION, Label.M_TARGET));
        for (Methodology.Term t : m.terms()) {
            rows.add(LayoutRow.of(text(0, orElse(t.text(), ""), StyleKind.VALUE),
                    text(1, orElse(t.resolution(), ""), StyleKind.VALUE),
                    text(2, orElse(t.target(), ""), StyleKind.VALUE)));
        }
        rows.add(tableHeader(Label.M_DATASET, Label.M_COLUMN, Label.M_DEFINITION, Label.M_REVISION));
        for (Methodology.DefinitionUse u : m.definitions()) {
            List<LayoutCell> cells = new ArrayList<>(List.of(text(0, u.datasetId(), StyleKind.VALUE),
                    text(1, outputLabel(u.datasetId(), u.output()), StyleKind.VALUE),
                    text(2, u.definition().kind() + " " + u.definition().key(), StyleKind.VALUE)));
            if (u.definition().revision() != null) {
                cells.add(integer(3, u.definition().revision()));
            }
            rows.add(new LayoutRow(cells));
        }

        rows.add(LayoutRow.EMPTY);
        rows.add(LayoutRow.of(label(0, Label.M_RULES, StyleKind.SECTION_TITLE)));
        pair(rows, Label.M_NULLS, Label.M_NULLS_RULE.in(lang));
        rows.add(tableHeader(Label.M_DATASET, Label.M_COLUMN, Label.M_ORDER, Label.M_COLLATION));
        for (Methodology.OrderRule o : m.orderRules()) {
            List<LayoutCell> cells = new ArrayList<>(List.of(text(0, o.datasetId(), StyleKind.VALUE),
                    text(1, outputLabel(o.datasetId(), o.output()), StyleKind.VALUE),
                    text(2, o.direction() + (o.nulls() == null ? "" : ", NULLS " + o.nulls()), StyleKind.VALUE)));
            if (o.collation() != null) {
                cells.add(text(3, o.collation(), StyleKind.VALUE));
            }
            rows.add(new LayoutRow(cells));
        }
        pair(rows, Label.M_EXCLUDED, fact(m.excludedColumns()));
        pair(rows, Label.M_MODEL, fact(m.model()));
        pair(rows, Label.M_UNITS, fact(m.units()));
        return rows;
    }

    private String outputLabel(String datasetId, String output) {
        return result.datasets().stream().filter(d -> d.datasetId().equals(datasetId)).findFirst()
                .flatMap(d -> d.columns().stream().filter(c -> c.output().equals(output)).findFirst())
                .map(ResultColumn2::label).orElse(output);
    }

    // ------------------------------------------------------------------ texts

    private String warning(ResultWarning w) {
        DatasetResult d = result.dataset(w.datasetId());
        String name = orElse(d.title(), d.datasetId());
        return switch (w.code()) {
            case ResultWarning.DATASET_TRUNCATED -> Label.WARNING_TRUNCATED.format(lang, name, w.delivered());
            case ResultWarning.CELL_TEXT_TRUNCATED -> Label.WARNING_TEXT_CUT.format(lang, name,
                    outputLabel(w.datasetId(), w.output()), w.delivered(), w.limit(), CellText.MARK);
            default -> w.code();
        };
    }

    private String filter(FilterDescription f) {
        String column = f.columnLabel() != null ? f.columnLabel() : qualified(f.sourceObject());
        String values = f.values().stream().map(this::literal).collect(Collectors.joining(", "));
        DatasetResult d = result.dataset(f.datasetId());
        String phrase = column + " " + Label.operator(f.operator()).in(lang) + (values.isEmpty() ? "" : " " + values);
        return orElse(d.title(), d.datasetId()) + ": " + phrase;
    }

    private String literal(SpecPresentation.Literal l) {
        if ("BOOLEAN".equals(l.type())) {
            return (Boolean.parseBoolean(l.value()) ? Label.YES : Label.NO).in(lang);
        }
        return l.value();
    }

    private static String qualified(SourceObject o) {
        return o.schema() + "." + o.name() + (o.column() == null ? "" : "." + o.column());
    }

    private String fact(Fact fact) {
        return switch (fact) {
            case Fact.Known k -> k.value();
            case Fact.Unavailable u -> Label.UNAVAILABLE.format(lang, Label.reason(u.reason(), lang));
        };
    }

    private String withId(Fact name, String id) {
        return switch (name) {
            case Fact.Known k -> k.value() + " (" + id + ")";
            case Fact.Unavailable u -> id + " (" + fact(u) + ")";
        };
    }

    private String when(Instant instant) {
        ZoneId zone = header.timeZone();
        return WHEN.format(instant.atZone(zone)) + " (" + zone.getId() + ")";
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ------------------------------------------------------------------ cells

    private void pair(RowStream rows, Label label, String value) {
        rows.add(LayoutRow.of(label(0, label), text(1, value, StyleKind.VALUE)));
    }

    private LayoutRow tableHeader(Label... labels) {
        List<LayoutCell> cells = new ArrayList<>();
        for (int j = 0; j < labels.length; j++) {
            cells.add(label(j, labels[j], StyleKind.TABLE_HEADER));
        }
        return new LayoutRow(cells);
    }

    private LayoutCell label(int column, Label label) {
        return label(column, label, StyleKind.LABEL);
    }

    private LayoutCell label(int column, Label label, StyleKind style) {
        return new LayoutCell(column, CellValue.text(label.in(lang)), style);
    }

    /**
     * A text of the header, a label or the methodology. Data texts were already limited by A4-B6; these are limited
     * here to Excel's maximum with the visible mark, never cut silently.
     */
    private static LayoutCell text(int column, String value, StyleKind style) {
        return new LayoutCell(column, CellValue.text(CellText.limit(value, EXCEL_MAX_TEXT)), style);
    }

    private static LayoutCell integer(int column, long value) {
        return new LayoutCell(column, new CellValue.Number(value, ValueFormat.INTEGER_FORMAT), StyleKind.VALUE);
    }
}
