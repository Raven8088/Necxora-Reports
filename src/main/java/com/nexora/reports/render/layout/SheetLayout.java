package com.nexora.reports.render.layout;

import java.util.Objects;

/**
 * One sheet: its name and its rows, produced on demand (a detail sheet maps the result's rows without copying them).
 *
 * @param freezeRows rows kept visible at the top (0: none)
 * @param filter     the range of the autofilter, or {@code null}
 * @param kind       what the sheet is (presentation, EX-19: gridlines and printing); it changes no cell
 */
public record SheetLayout(String name, Iterable<LayoutRow> rows, int freezeRows, FilterRange filter, Kind kind) {

    /** «Reporte», a detail table, or «Metodología». */
    public enum Kind {
        REPORT, DETAIL, METHODOLOGY
    }

    public SheetLayout {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(kind, "kind");
        if (freezeRows < 0) {
            throw new IllegalArgumentException("negative freeze");
        }
    }

    /** A sheet presented as a detail table. */
    public SheetLayout(String name, Iterable<LayoutRow> rows, int freezeRows, FilterRange filter) {
        this(name, rows, freezeRows, filter, Kind.DETAIL);
    }

    /** 0-based, inclusive. */
    public record FilterRange(int firstRow, int lastRow, int firstColumn, int lastColumn) {
    }
}
