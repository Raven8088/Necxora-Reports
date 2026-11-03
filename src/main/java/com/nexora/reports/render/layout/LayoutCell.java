package com.nexora.reports.render.layout;

import java.util.Objects;

/** One cell of a row: its 0-based column, its value and its style. */
public record LayoutCell(int column, CellValue value, StyleKind style) {

    public LayoutCell {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(style, "style");
        if (column < 0) {
            throw new IllegalArgumentException("negative column");
        }
    }

    public boolean written() {
        return !(value instanceof CellValue.Blank);
    }
}
