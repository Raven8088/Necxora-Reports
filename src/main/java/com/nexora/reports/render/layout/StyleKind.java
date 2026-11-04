package com.nexora.reports.render.layout;

/**
 * Presentation role of a cell; the writer maps each kind (with the {@link ValueKind} of the cell) to a style of the
 * presentation theme and decides whether its text sets the width of its column (EX-13, EX-19).
 */
public enum StyleKind {
    TITLE, LABEL, VALUE, NOTE, WARNING, SECTION_TITLE, ITEM_TITLE, TABLE_HEADER, DATA, TOTAL_LABEL, TOTAL_VALUE,
    /**
     * The value of a field of the report header (request, source, tenant, filters...). It stands alone in its row and
     * runs over the empty cells to its right; it never widens the columns of the tables below it (EX-19).
     */
    HEADER_VALUE;

    public boolean bold() {
        return this != VALUE && this != DATA && this != LABEL && this != NOTE && this != HEADER_VALUE;
    }

    /**
     * Whether the cell's text sets the width of its column. Titles, notes, warnings and the values of the report
     * header stand alone in their row and may run over the empty cells to their right, so they do not widen the
     * column of a table (EX-13, EX-19).
     */
    public boolean sizesColumn() {
        return this != TITLE && this != SECTION_TITLE && this != ITEM_TITLE && this != NOTE && this != WARNING
                && this != HEADER_VALUE;
    }

    /** Whether the cell belongs to a table (header, data or total): it gets borders and the table's alignment. */
    public boolean inTable() {
        return this == TABLE_HEADER || this == DATA || this == TOTAL_LABEL || this == TOTAL_VALUE;
    }
}
