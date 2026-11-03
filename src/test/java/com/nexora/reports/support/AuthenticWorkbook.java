package com.nexora.reports.support;

import com.nexora.reports.render.xlsx.Workbooks;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B10-c.1: compares a downloaded Excel file, cell by cell, with independent reference rows, using only the
 * specification of the report (its sections, items, labels and groups) to know where each dataset is. Nothing here
 * depends on a particular report: the same comparison serves every case.
 * <p>
 * Layout of {@code nexora-excel-report/1} as written by Reports (EX-01 to EX-10): sheet «Reporte» first and
 * «Metodología» last; a {@code summaryTable} is in «Reporte» as a header row with the visible labels, one row per
 * group and the row «Total general» with the measures of its total dataset; every {@code table} has its own sheet, in
 * the order of the items, with the labels and then the rows. Numbers are Excel numbers (compared as doubles); a null
 * group key is «(sin valor)»; a null detail or measure value is an empty cell.
 */
public final class AuthenticWorkbook {

    public static final String NO_VALUE = "(sin valor)";
    public static final String TOTAL = "Total general";

    private AuthenticWorkbook() {
    }

    /** Asserts every summary, total and detail cell of {@code xlsx} against {@code reference} (dataset id → rows). */
    public static void assertCells(Map<String, Object> spec, byte[] xlsx, Map<String, List<List<Object>>> reference) {
        try (XSSFWorkbook wb = Workbooks.open(xlsx)) {
            List<Map<String, Object>> tables = new ArrayList<>();
            List<List<Object>> report = Workbooks.rows(wb.getSheet("Reporte"));
            assertThat(wb.getSheetName(0)).isEqualTo("Reporte");
            for (Object section : l(spec.get("sections"))) {
                for (Object item : l(m(section).get("items"))) {
                    Map<String, Object> it = m(item);
                    Map<String, Object> dataset = dataset(spec, (String) it.get("dataset"));
                    if ("summaryTable".equals(it.get("type"))) {
                        assertSummary(report, dataset, it.get("total") == null ? null
                                : dataset(spec, (String) it.get("total")), reference);
                    } else {
                        tables.add(dataset);
                    }
                }
            }
            assertThat(wb.getNumberOfSheets()).as("Reporte, one sheet per table, Metodología")
                    .isEqualTo(2 + tables.size());
            assertThat(wb.getSheetName(wb.getNumberOfSheets() - 1)).isEqualTo("Metodología");
            for (int i = 0; i < tables.size(); i++) {
                Map<String, Object> dataset = tables.get(i);
                List<List<Object>> sheet = Workbooks.rows(wb.getSheetAt(1 + i));
                List<List<Object>> expected = reference.get((String) dataset.get("id"));
                assertThat(sheet.getFirst()).as("labels of " + dataset.get("id")).isEqualTo(labels(dataset));
                assertThat(sheet.size()).as("rows of " + dataset.get("id")).isEqualTo(1 + expected.size());
                for (int r = 0; r < expected.size(); r++) {
                    assertThat(sheet.get(1 + r)).as(dataset.get("id") + " row " + r)
                            .isEqualTo(trimmed(new ArrayList<>(expected.get(r))));
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void assertSummary(List<List<Object>> report, Map<String, Object> dataset,
                                      Map<String, Object> total, Map<String, List<List<Object>>> reference) {
        List<Object> labels = labels(dataset);
        int header = indexOf(report, labels);
        List<Map<String, Object>> columns = visible(dataset);
        List<Object> groups = l(dataset.get("groupBy"));
        List<List<Object>> rows = reference.get((String) dataset.get("id"));
        for (int r = 0; r < rows.size(); r++) {
            List<Object> expected = new ArrayList<>();
            for (int c = 0; c < columns.size(); c++) {
                Object value = rows.get(r).get(c);
                expected.add(value == null && groups.contains(columns.get(c).get("id")) ? NO_VALUE : value);
            }
            assertThat(report.get(header + 1 + r)).as(dataset.get("id") + " row " + r).isEqualTo(trimmed(expected));
        }
        if (total == null) {
            return;
        }
        List<Object> totalRow = reference.get((String) total.get("id")).getFirst();
        List<Object> totalIds = visible(total).stream().map(c -> c.get("id")).toList();
        List<Object> expected = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Object id = columns.get(c).get("id");
            if (c == 0) {
                expected.add(TOTAL);
            } else if (groups.contains(id)) {
                expected.add(null);
            } else {
                expected.add(totalRow.get(totalIds.indexOf(id)));
            }
        }
        assertThat(report.get(header + 1 + rows.size())).as("total of " + dataset.get("id"))
                .isEqualTo(trimmed(expected));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataset(Map<String, Object> spec, String id) {
        return (Map<String, Object>) l(spec.get("datasets")).stream().filter(d -> id.equals(m(d).get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("no dataset " + id));
    }

    private static List<Map<String, Object>> visible(Map<String, Object> dataset) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Object s : l(dataset.get("select"))) {
            if (!Boolean.TRUE.equals(m(s).get("hidden"))) {
                columns.add(m(s));
            }
        }
        return columns;
    }

    public static List<Object> labels(Map<String, Object> dataset) {
        return visible(dataset).stream().map(c -> c.get("label")).toList();
    }

    /** The first row of {@code rows} equal to {@code cells}. */
    public static int indexOf(List<List<Object>> rows, List<Object> cells) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).equals(cells)) {
                return i;
            }
        }
        throw new AssertionError("no row " + cells);
    }

    /** The first row starting with {@code first}. */
    public static List<Object> rowStartingWith(List<List<Object>> rows, Object first) {
        for (List<Object> row : rows) {
            if (!row.isEmpty() && first.equals(row.getFirst())) {
                return row;
            }
        }
        throw new AssertionError("no row starting with " + first);
    }

    /** Excel holds no trailing empty cells. */
    private static List<Object> trimmed(List<Object> row) {
        List<Object> copy = new ArrayList<>(row);
        while (!copy.isEmpty() && copy.getLast() == null) {
            copy.removeLast();
        }
        return Arrays.asList(copy.toArray());
    }
}
