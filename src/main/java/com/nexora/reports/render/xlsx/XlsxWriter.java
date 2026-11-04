package com.nexora.reports.render.xlsx;

import com.nexora.reports.render.layout.CellValue;
import com.nexora.reports.render.layout.LayoutCell;
import com.nexora.reports.render.layout.LayoutRow;
import com.nexora.reports.render.layout.SheetLayout;
import com.nexora.reports.render.layout.StyleKind;
import com.nexora.reports.render.layout.WorkbookLayout;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes a {@link WorkbookLayout} as XLSX with Apache POI's streaming workbook (D-B7-3): at most {@code rowWindow}
 * rows in memory, inline strings, compressed temporary files, removed by {@link SXSSFWorkbook#close()} in every case
 * (precision 8; B1-O1: {@code dispose()} is deprecated in POI 5.4.0 and not used). It never writes a formula: texts
 * are string cells, with the quote prefix when they start with a formula trigger (EX-11); no merged cells, no links,
 * no names beyond the autofilter's and the print titles of a detail sheet (EX-13, EX-19).
 * <p>
 * Presentation (D-XL-1 to D-XL-8, EX-19): every cell gets the style of the {@link PresentationTheme} for its kind and
 * the category of its value ({@link XlsxStyles}, one style per combination, cached); the grid (sheets, cells, values,
 * types, number formats, frozen rows, autofilters) is exactly what the layout says. Column widths are computed from
 * what is written, by kind of value; long table headers wrap (up to three lines) instead of widening their column;
 * «Reporte» and «Metodología» hide the gridlines; every sheet prints landscape when it has more than
 * {@value #PORTRAIT_MAX_COLUMNS} columns and with the page number in the footer; a detail sheet also prints fitted to
 * one page wide and repeats its header row on every printed page («Reporte» and «Metodología» are not fitted: their
 * long texts, such as the request, run over the empty cells to the right and would shrink the whole page). The title of the report is the title property of the file (never the request).
 * <p>
 * The package is written without Zip64 records ({@link Zip64Mode#Never}): POI's streaming default writes Zip64 data
 * descriptors that local headers do not announce, which sequential readers such as LibreOffice and
 * {@code java.util.zip.ZipInputStream} reject (A4-B7, observation B7-O1). An artifact is at most 20 MiB, far from
 * the 4 GiB that would need Zip64.
 */
final class XlsxWriter {

    static final String CREATOR = "Nexora AI";
    static final int MIN_WIDTH = 8;
    static final int MAX_WIDTH = 60;
    /** A table header longer than this wraps (EX-19) instead of widening its column, up to three lines. */
    static final int HEADER_WRAP = 24;
    static final int HEADER_MAX_LINES = 3;
    /** Room for the button of the autofilter next to a header. */
    static final int FILTER_BUTTON = 2;
    static final int PORTRAIT_MAX_COLUMNS = 6;
    static final int MAX_TITLE_PROPERTY = 255;

    private XlsxWriter() {
    }

    /** Writes {@code layout} with the Nexora theme to {@code out} (not closed) and returns the number of cells written. */
    static long write(WorkbookLayout layout, Instant created, int rowWindow, OutputStream out) throws IOException {
        return write(layout, created, rowWindow, PresentationTheme.DEFAULT, out);
    }

    /** Writes {@code layout} with {@code theme} to {@code out} (not closed) and returns the number of cells written. */
    static long write(WorkbookLayout layout, Instant created, int rowWindow, PresentationTheme theme, OutputStream out)
            throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, rowWindow, true, false)) {
            workbook.setZip64Mode(Zip64Mode.Never);
            XlsxStyles styles = new XlsxStyles(workbook, theme);
            var properties = workbook.getXSSFWorkbook().getProperties().getCoreProperties();
            properties.setCreator(CREATOR);
            properties.setCreated(Optional.of(Date.from(created)));
            String title = titleProperty(layout.title());
            if (!title.isEmpty()) {
                properties.setTitle(title);
            }
            String footer = footer(layout.pageFooter());
            long cells = 0;
            for (SheetLayout sheetLayout : layout.sheets()) {
                cells += sheet(workbook, styles, sheetLayout, footer);
            }
            workbook.write(out);
            return cells;
        }
    }

    private static long sheet(SXSSFWorkbook workbook, XlsxStyles styles, SheetLayout layout, String footer) {
        SXSSFSheet sheet = workbook.createSheet(layout.name());
        int filterButton = layout.filter() == null ? 0 : FILTER_BUTTON;
        Map<Integer, Integer> widths = new HashMap<>();
        int lastColumn = -1;
        long cells = 0;
        int r = 0;
        for (LayoutRow layoutRow : layout.rows()) {
            if (!layoutRow.cells().isEmpty()) {
                Row row = sheet.createRow(r);
                int lines = 1;
                boolean band = styles.theme().banding() && r % 2 == 0;
                for (LayoutCell c : layoutRow.cells()) {
                    if (!c.written()) {
                        continue;
                    }
                    Cell cell = row.createCell(c.column());
                    boolean dataBand = band && c.style() == StyleKind.DATA;
                    int width = switch (c.value()) {
                        case CellValue.Text t -> {
                            cell.setCellValue(t.value());
                            cell.setCellStyle(styles.get(c.style(), c.kind(), null, t.quotePrefix(), dataBand));
                            int length = longestLine(t.value());
                            if (c.style() == StyleKind.TABLE_HEADER) {
                                int w = headerWidth(length);
                                lines = Math.max(lines, w == 0 ? 1 : (length + w - 1) / w);
                                yield w + filterButton;
                            }
                            yield length;
                        }
                        case CellValue.Number n -> {
                            cell.setCellValue(n.value());
                            cell.setCellStyle(styles.get(c.style(), c.kind(), n.format(), false, dataBand));
                            yield numberWidth(n.value(), n.format());
                        }
                        case CellValue.DateTime d -> {
                            cell.setCellValue(d.value());
                            cell.setCellStyle(styles.get(c.style(), c.kind(), d.format(), false, dataBand));
                            yield d.format().length();
                        }
                        case CellValue.Blank b -> 0;
                    };
                    if (c.style().sizesColumn()) {
                        widths.merge(c.column(), width, Math::max);
                    }
                    lastColumn = Math.max(lastColumn, c.column());
                    cells++;
                }
                if (lines > 1) {
                    row.setHeightInPoints(styles.lineHeight() * lines + 2);
                }
            }
            r++;
        }
        widths.forEach((column, chars) -> sheet.setColumnWidth(column,
                Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, chars + 2)) * 256));
        if (layout.freezeRows() > 0) {
            sheet.createFreezePane(0, layout.freezeRows());
        }
        SheetLayout.FilterRange f = layout.filter();
        if (f != null) {
            sheet.setAutoFilter(new CellRangeAddress(f.firstRow(), f.lastRow(), f.firstColumn(), f.lastColumn()));
        }
        if (layout.kind() != SheetLayout.Kind.DETAIL) {
            sheet.setDisplayGridlines(false);
        }
        print(sheet, layout, lastColumn, footer);
        return cells;
    }

    /** Landscape for wide sheets and page numbers; a detail one page wide with its header row on every page. */
    private static void print(SXSSFSheet sheet, SheetLayout layout, int lastColumn, String footer) {
        PrintSetup setup = sheet.getPrintSetup();
        setup.setLandscape(lastColumn + 1 > PORTRAIT_MAX_COLUMNS);
        sheet.getFooter().setCenter(footer);
        if (layout.kind() == SheetLayout.Kind.DETAIL) {
            sheet.setFitToPage(true);
            setup.setFitWidth((short) 1);
            setup.setFitHeight((short) 0);
            if (layout.freezeRows() > 0) {
                sheet.setRepeatingRows(new CellRangeAddress(0, layout.freezeRows() - 1, -1, -1));
            }
        }
    }

    /**
     * The width a table header asks for: its length up to {@value #HEADER_WRAP} characters; a longer header wraps,
     * and its column is wide enough to hold it in {@value #HEADER_MAX_LINES} lines.
     */
    static int headerWidth(int length) {
        return Math.min(MAX_WIDTH - 2, Math.max(Math.min(length, HEADER_WRAP),
                (length + HEADER_MAX_LINES - 1) / HEADER_MAX_LINES));
    }

    /** The characters a number takes with its format: digits, thousands separators, decimals and sign. */
    static int numberWidth(double value, String format) {
        double whole = Math.floor(Math.abs(value));
        int digits = 1;
        if (whole >= 1e15) {
            digits = (int) Math.floor(Math.log10(whole)) + 1;
        } else {
            for (long w = (long) whole; w >= 10; w /= 10) {
                digits++;
            }
        }
        int point = format.indexOf('.');
        int decimals = point < 0 ? 0 : format.length() - point - 1;
        int separators = format.contains(",") ? (digits - 1) / 3 : 0;
        return digits + separators + (decimals > 0 ? decimals + 1 : 0) + (value < 0 ? 1 : 0);
    }

    /** The code points of the longest line ({@code \n}, {@code \r} or {@code \r\n}), without copying the text. */
    static int longestLine(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                longest = Math.max(longest, current);
                current = 0;
            } else if (!Character.isLowSurrogate(ch)) {
                current++;
            }
        }
        return Math.max(longest, current);
    }

    /**
     * The printed footer: the layout's text with {@code &} escaped ({@code &&}: in a footer {@code &} starts a code)
     * and control characters removed; then {@code {0}} becomes the page number ({@code &P}) and {@code {1}} the
     * number of pages ({@code &N}). No other code is ever written.
     */
    static String footer(String text) {
        return clean(text).replace("&", "&&").replace("{0}", "&P").replace("{1}", "&N");
    }

    /** The title property: the report's title without control characters, at most 255 characters. */
    static String titleProperty(String title) {
        String clean = clean(title).strip();
        if (clean.length() <= MAX_TITLE_PROPERTY) {
            return clean;
        }
        int end = MAX_TITLE_PROPERTY;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return clean.substring(0, end);
    }

    private static String clean(String text) {
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> b.appendCodePoint(Character.isISOControl(cp) || cp == 0xFFFE || cp == 0xFFFF
                || (cp >= 0xD800 && cp <= 0xDFFF) ? ' ' : cp));
        return b.toString();
    }
}
