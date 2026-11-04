package com.nexora.reports.render.layout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Every sheet of the workbook, in order, with the counts the renderer checks before writing.
 *
 * @param pageFooter the printed page footer in the report's language, with {@code {0}} for the page number and
 *                   {@code {1}} for the number of pages (EX-19); it is not a cell
 */
public record WorkbookLayout(String title, List<SheetLayout> sheets, String pageFooter) {

    /** Without a language: the footer is «{0} / {1}». */
    public static final String NEUTRAL_PAGE_FOOTER = "{0} / {1}";

    /**
     * Lower bound of the compression of spreadsheet XML assumed by {@link #minimumBytes()}: a workbook can be refused
     * before it is written only if it could not fit even compressed this much (D-B7-5); the exact limit is enforced
     * while writing.
     */
    public static final int ASSUMED_MAX_COMPRESSION = 10;

    public WorkbookLayout {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(pageFooter, "pageFooter");
        sheets = List.copyOf(sheets);
    }

    public WorkbookLayout(String title, List<SheetLayout> sheets) {
        this(title, sheets, NEUTRAL_PAGE_FOOTER);
    }

    public List<String> sheetNames() {
        return sheets.stream().map(SheetLayout::name).toList();
    }

    /** Cells that will be written (blank cells are not). */
    public long cellCount() {
        long cells = 0;
        for (SheetLayout sheet : sheets) {
            for (LayoutRow row : sheet.rows()) {
                for (LayoutCell cell : row.cells()) {
                    if (cell.written()) {
                        cells++;
                    }
                }
            }
        }
        return cells;
    }

    /**
     * A conservative lower bound of the artifact's size: the UTF-8 bytes of its texts and about 8 bytes per other
     * value, as if the XML compressed {@value #ASSUMED_MAX_COMPRESSION} to 1. If even this exceeds the limit, the
     * artifact is refused before writing.
     */
    public long minimumBytes() {
        long bytes = 0;
        for (SheetLayout sheet : sheets) {
            for (LayoutRow row : sheet.rows()) {
                for (LayoutCell cell : row.cells()) {
                    bytes += switch (cell.value()) {
                        case CellValue.Text t -> t.value().getBytes(StandardCharsets.UTF_8).length;
                        case CellValue.Blank b -> 0;
                        default -> 8;
                    };
                }
            }
        }
        return bytes / ASSUMED_MAX_COMPRESSION;
    }
}
