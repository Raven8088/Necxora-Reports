package com.nexora.reports.render.layout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Every sheet of the workbook, in order, with the counts the renderer checks before writing. */
public record WorkbookLayout(String title, List<SheetLayout> sheets) {

    /**
     * Lower bound of the compression of spreadsheet XML assumed by {@link #minimumBytes()}: a workbook can be refused
     * before it is written only if it could not fit even compressed this much (D-B7-5); the exact limit is enforced
     * while writing.
     */
    public static final int ASSUMED_MAX_COMPRESSION = 10;

    public WorkbookLayout {
        Objects.requireNonNull(title, "title");
        sheets = List.copyOf(sheets);
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
