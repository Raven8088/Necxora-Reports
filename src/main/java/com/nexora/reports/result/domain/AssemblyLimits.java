package com.nexora.reports.result.domain;

/**
 * Limits of an assembled report (A4 proposal v3, section 4 bis.5; D-B6-3).
 *
 * @param maxCells    cells of the whole presentation, data and everything a renderer adds (see
 *                    {@link PresentationBudget}); initially 600 000
 * @param maxCellText UTF-16 code units of a text cell, mark included; Excel's maximum, 32 767, is also the ceiling
 */
public record AssemblyLimits(long maxCells, int maxCellText) {

    public static final int EXCEL_MAX_CELL_TEXT = 32_767;
    public static final AssemblyLimits DEFAULTS = new AssemblyLimits(600_000, EXCEL_MAX_CELL_TEXT);

    public AssemblyLimits {
        if (maxCells < 1 || maxCellText < CellText.MARK.length() + 2 || maxCellText > EXCEL_MAX_CELL_TEXT) {
            throw new IllegalArgumentException("invalid assembly limits");
        }
    }
}
