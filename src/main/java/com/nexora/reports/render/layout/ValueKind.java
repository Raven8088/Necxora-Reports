package com.nexora.reports.render.layout;

/**
 * Presentation of the Excel reports (D-XL-1, EX-19): the category of the value of a cell, derived from the generic
 * type of its column ({@code OutputValueType}) and its role, never from its name or content. The writer aligns and
 * styles by it; it never changes the value, its type or its number format.
 */
public enum ValueKind {
    TEXT,
    INTEGER,
    DECIMAL,
    DATE,
    DATE_TIME,
    BOOLEAN,
    /** The «(sin valor)» of a null group key (EX-10): a text, shown apart from the real values. */
    NO_VALUE;

    /** The kind of a value written as it is: what the cell holds tells the category (text, number, date). */
    public static ValueKind of(CellValue value) {
        return switch (value) {
            case CellValue.Number n -> ValueFormat.INTEGER_FORMAT.equals(n.format()) ? INTEGER : DECIMAL;
            case CellValue.DateTime d -> ValueFormat.DATE_FORMAT.equals(d.format()) ? DATE : DATE_TIME;
            case CellValue.Text t -> TEXT;
            case CellValue.Blank b -> TEXT;
        };
    }
}
