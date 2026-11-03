package com.nexora.reports.render.layout;

import java.util.List;

/** One row of a sheet; an empty row is a separator and writes nothing. */
public record LayoutRow(List<LayoutCell> cells) {

    public static final LayoutRow EMPTY = new LayoutRow(List.of());

    public LayoutRow {
        cells = List.copyOf(cells);
    }

    public static LayoutRow of(LayoutCell... cells) {
        return new LayoutRow(List.of(cells));
    }
}
