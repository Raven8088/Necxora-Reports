package com.nexora.reports.render.xlsx;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Presentation of the Excel reports (D-XL-1, «misma rejilla»): everything of a written workbook that the presentation
 * must NOT change, as text. Sheets (order and names), frozen rows, autofilter range, merged regions, and for every
 * row and cell: its address, type, exact value (strings verbatim, numbers and dates as the stored double), number
 * format and quote prefix. Fonts, fills, borders, alignment, column widths, row heights, gridlines and print settings
 * are deliberately left out: they are what the presentation may change.
 */
public final class GridSnapshot {

    private GridSnapshot() {
    }

    public static String of(byte[] xlsx) {
        StringBuilder out = new StringBuilder();
        try (XSSFWorkbook wb = Workbooks.open(xlsx)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                XSSFSheet sheet = wb.getSheetAt(s);
                out.append("== sheet ").append(s).append(' ').append(quote(sheet.getSheetName())).append('\n');
                PaneInformation pane = sheet.getPaneInformation();
                out.append("freeze ").append(pane == null || !pane.isFreezePane() ? "none"
                        : pane.getHorizontalSplitPosition() + "," + pane.getVerticalSplitPosition()).append('\n');
                out.append("filter ").append(sheet.getCTWorksheet().isSetAutoFilter()
                        ? sheet.getCTWorksheet().getAutoFilter().getRef() : "none").append('\n');
                out.append("merged ").append(sheet.getNumMergedRegions()).append('\n');
                out.append("lastRow ").append(sheet.getLastRowNum()).append('\n');
                for (Row row : sheet) {
                    for (Cell c : row) {
                        out.append(c.getAddress()).append('\t').append(c.getCellType()).append('\t')
                                .append(value(c)).append('\t').append(quote(c.getCellStyle().getDataFormatString()))
                                .append('\t').append(c.getCellStyle().getQuotePrefixed() ? "quote" : "-").append('\n');
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toString();
    }

    private static String value(Cell c) {
        if (c.getCellType() == CellType.STRING) {
            return quote(c.getStringCellValue());
        }
        if (c.getCellType() == CellType.NUMERIC) {
            return Double.toString(c.getNumericCellValue());
        }
        if (c.getCellType() == CellType.BLANK) {
            return "blank";
        }
        return "UNEXPECTED " + c.getCellType();
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') {
                b.append('\\').append(ch);
            } else if (ch < 0x20) {
                b.append(String.format("\\u%04x", (int) ch));
            } else {
                b.append(ch);
            }
        }
        return b.append('"').toString();
    }
}
