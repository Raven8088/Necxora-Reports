package com.nexora.reports.render.xlsx;

import com.nexora.reports.render.layout.CellValue;
import com.nexora.reports.render.layout.LayoutCell;
import com.nexora.reports.render.layout.LayoutRow;
import com.nexora.reports.render.layout.SheetLayout;
import com.nexora.reports.render.layout.StyleKind;
import com.nexora.reports.render.layout.WorkbookLayout;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
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
 * no names beyond the autofilter's (EX-13).
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

    private XlsxWriter() {
    }

    /** Writes {@code layout} to {@code out} (not closed) and returns the number of cells written. */
    static long write(WorkbookLayout layout, Instant created, int rowWindow, OutputStream out) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, rowWindow, true, false)) {
            workbook.setZip64Mode(Zip64Mode.Never);
            Styles styles = new Styles(workbook);
            var properties = workbook.getXSSFWorkbook().getProperties().getCoreProperties();
            properties.setCreator(CREATOR);
            properties.setCreated(Optional.of(Date.from(created)));
            long cells = 0;
            for (SheetLayout sheetLayout : layout.sheets()) {
                cells += sheet(workbook, styles, sheetLayout);
            }
            workbook.write(out);
            return cells;
        }
    }

    private static long sheet(SXSSFWorkbook workbook, Styles styles, SheetLayout layout) {
        SXSSFSheet sheet = workbook.createSheet(layout.name());
        Map<Integer, Integer> widths = new HashMap<>();
        long cells = 0;
        int r = 0;
        for (LayoutRow layoutRow : layout.rows()) {
            if (!layoutRow.cells().isEmpty()) {
                Row row = sheet.createRow(r);
                for (LayoutCell c : layoutRow.cells()) {
                    if (!c.written()) {
                        continue;
                    }
                    Cell cell = row.createCell(c.column());
                    int width = switch (c.value()) {
                        case CellValue.Text t -> {
                            cell.setCellValue(t.value());
                            cell.setCellStyle(styles.get(c.style(), null, t.quotePrefix()));
                            yield t.value().lines().mapToInt(String::length).max().orElse(0);
                        }
                        case CellValue.Number n -> {
                            cell.setCellValue(n.value());
                            cell.setCellStyle(styles.get(c.style(), n.format(), false));
                            yield 16;
                        }
                        case CellValue.DateTime d -> {
                            cell.setCellValue(d.value());
                            cell.setCellStyle(styles.get(c.style(), d.format(), false));
                            yield d.format().length();
                        }
                        case CellValue.Blank b -> 0;
                    };
                    if (c.style().sizesColumn()) {
                        widths.merge(c.column(), width, Math::max);
                    }
                    cells++;
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
        return cells;
    }

    /** One cell style per (kind, number format, quote prefix): a workbook allows at most 64 000. */
    private static final class Styles {
        private final SXSSFWorkbook workbook;
        private final DataFormat formats;
        private final Font bold;
        private final Map<String, CellStyle> cache = new HashMap<>();

        Styles(SXSSFWorkbook workbook) {
            this.workbook = workbook;
            this.formats = workbook.createDataFormat();
            this.bold = workbook.createFont();
            bold.setBold(true);
        }

        CellStyle get(StyleKind kind, String format, boolean quotePrefix) {
            return cache.computeIfAbsent(kind + "|" + format + "|" + quotePrefix, k -> {
                CellStyle style = workbook.createCellStyle();
                if (kind.bold()) {
                    style.setFont(bold);
                }
                if (format != null) {
                    style.setDataFormat(formats.getFormat(format));
                }
                style.setQuotePrefixed(quotePrefix);
                return style;
            });
        }
    }
}
