package com.nexora.reports.render.layout;

/** Presentation role of a cell; the writer maps each kind to a style (bold, number format) and a column width. */
public enum StyleKind {
    TITLE, LABEL, VALUE, NOTE, WARNING, SECTION_TITLE, ITEM_TITLE, TABLE_HEADER, DATA, TOTAL_LABEL, TOTAL_VALUE;

    public boolean bold() {
        return this != VALUE && this != DATA && this != LABEL && this != NOTE;
    }

    /**
     * Whether the cell's text sets the width of its column. Titles, notes and warnings stand alone in their row and
     * may run over the empty cells to their right, so they do not widen the column of a table (EX-13).
     */
    public boolean sizesColumn() {
        return this != TITLE && this != SECTION_TITLE && this != ITEM_TITLE && this != NOTE && this != WARNING;
    }
}
