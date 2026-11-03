package com.nexora.reports.render;

import com.nexora.reports.render.layout.CellValue;
import com.nexora.reports.render.layout.LayoutCell;
import com.nexora.reports.render.layout.LayoutRow;
import com.nexora.reports.render.layout.SheetLayout;
import com.nexora.reports.render.layout.WorkbookLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test-only views of a layout: each row as the plain values of its cells, by column (null where none). */
public final class LayoutAccess {

    private LayoutAccess() {
    }

    public static SheetLayout sheet(WorkbookLayout layout, String name) {
        return layout.sheets().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    public static List<List<Object>> rows(SheetLayout sheet) {
        List<List<Object>> rows = new ArrayList<>();
        for (LayoutRow row : sheet.rows()) {
            rows.add(values(row));
        }
        return rows;
    }

    public static List<Object> values(LayoutRow row) {
        int width = row.cells().stream().mapToInt(LayoutCell::column).max().orElse(-1) + 1;
        Object[] values = new Object[width];
        for (LayoutCell c : row.cells()) {
            values[c.column()] = plain(c.value());
        }
        return Arrays.asList(values);
    }

    public static Object plain(CellValue value) {
        return switch (value) {
            case CellValue.Text t -> t.value();
            case CellValue.Number n -> n.value();
            case CellValue.DateTime d -> d.value();
            case CellValue.Blank b -> null;
        };
    }

    /** The index of the first row whose first cell is {@code text}. */
    public static int find(List<List<Object>> rows, Object text) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && text.equals(rows.get(i).getFirst())) {
                return i;
            }
        }
        throw new AssertionError("no row starting with " + text);
    }

    /** The value next to the label {@code label}: the second cell of the first row whose first cell it is. */
    public static Object valueOf(List<List<Object>> rows, String label) {
        return rows.get(find(rows, label)).get(1);
    }
}
