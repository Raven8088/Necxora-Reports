package com.nexora.reports.render.layout;

import java.util.Objects;

/**
 * One sheet: its name and its rows, produced on demand (a detail sheet maps the result's rows without copying them).
 *
 * @param freezeRows rows kept visible at the top (0: none)
 * @param filter     the range of the autofilter, or {@code null}
 */
public record SheetLayout(String name, Iterable<LayoutRow> rows, int freezeRows, FilterRange filter) {

    public SheetLayout {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rows, "rows");
        if (freezeRows < 0) {
            throw new IllegalArgumentException("negative freeze");
        }
    }

    /** 0-based, inclusive. */
    public record FilterRange(int firstRow, int lastRow, int firstColumn, int lastColumn) {
    }
}
