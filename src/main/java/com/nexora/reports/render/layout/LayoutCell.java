package com.nexora.reports.render.layout;

import java.util.Objects;

/**
 * One cell of a row: its 0-based column, its value, its style and the category of its value (presentation, D-XL-1:
 * the writer aligns and styles by it; it never changes the value).
 */
public record LayoutCell(int column, CellValue value, StyleKind style, ValueKind kind) {

    public LayoutCell {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(kind, "kind");
        if (column < 0) {
            throw new IllegalArgumentException("negative column");
        }
    }

    /** A cell whose category is what its value holds ({@link ValueKind#of}). */
    public LayoutCell(int column, CellValue value, StyleKind style) {
        this(column, value, style, ValueKind.of(Objects.requireNonNull(value, "value")));
    }

    public boolean written() {
        return !(value instanceof CellValue.Blank);
    }
}
