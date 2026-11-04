package com.nexora.reports.render.xlsx;

import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.render.layout.CellValue;
import com.nexora.reports.render.layout.LayoutCell;
import com.nexora.reports.render.layout.LayoutRow;
import com.nexora.reports.render.layout.SheetLayout;
import com.nexora.reports.render.layout.StyleKind;
import com.nexora.reports.render.layout.WorkbookLayout;
import com.nexora.reports.result.ReportFixtures;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.ReportResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B7 (EX-11, EX-13; precision 6): no formula can be introduced by any external text, data, labels, titles,
 * filters or methodology, and the package carries no links, macros, connections or names beyond the autofilter.
 */
class XlsxSecurityTest {

    private static final List<String> HOSTILE = List.of("=HYPERLINK(\"http://x.invalid\",\"x\")", "+cmd|' /C calc'!A0",
            "-2+3", "@SUM(1+1)", "\t=1");

    private static byte[] render(ReportResult result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
        return out.toByteArray();
    }

    @Test
    void aDataValueThatLooksLikeAFormulaIsLiteralText() throws IOException {
        try (XSSFWorkbook wb = Workbooks.open(render(RenderFixtures.paA()))) {
            Cell cuenca = find(wb, "=Cuenca-9");
            assertThat(cuenca.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cuenca.getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(cuenca.getSheet().getSheetName()).isEqualTo("Listado de pozos");
            assertNoFormula(wb);
        }
    }

    @Test
    void hostileTextsInTheTitleRequestSectionsFiltersAndDataStayLiteral() throws IOException {
        ReportFixtures.Empresas documents = new ReportFixtures.Empresas(TestPlans.BENCH);
        documents.spec.put("title", HOSTILE.get(0));
        m(documents.spec.get("request")).put("text", HOSTILE.get(1));
        m(l(documents.spec.get("sections")).getFirst()).put("title", HOSTILE.get(2));
        Map<String, Object> detail = m(l(documents.spec.get("datasets")).get(2));
        detail.put("title", HOSTILE.get(3));
        Map<String, Object> in = m(l(detail.get("filters")).getFirst());
        m(l(in.get("values")).getFirst()).put("value", HOSTILE.get(4));
        for (Object op : l(documents.plan("detalle_empresas").get("operations"))) {
            if ("FILTER".equals(m(op).get("op"))) {
                for (Object p : l(m(op).get("predicates"))) {
                    if ("IN".equals(m(p).get("operator"))) {
                        m(l(m(p).get("values")).getFirst()).put("value", HOSTILE.get(4));
                    }
                }
            }
        }
        documents.reseal();
        List<List<List<Object>>> rows = RenderFixtures.empresasRows();
        rows.set(0, new ArrayList<>(List.of(List.of(HOSTILE.get(1), 1L), List.of("Operadora Ficticia Uno", 3L))));
        ReportResult result = RenderFixtures.empresas(documents, rows, RenderFixtures.complete(3));
        try (XSSFWorkbook wb = Workbooks.open(render(result))) {
            assertNoFormula(wb);
            int literal = 0;
            for (Sheet s : wb) {
                for (Row r : s) {
                    for (Cell c : r) {
                        String text = c.getCellType() == CellType.STRING ? c.getStringCellValue() : "";
                        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) {
                            assertThat(c.getCellStyle().getQuotePrefixed()).as(text).isTrue();
                            literal++;
                        }
                    }
                }
            }
            assertThat(literal >= 5).as("literal cells " + literal).isTrue();
            assertThat(wb.getSheetName(1)).isEqualTo("@SUM(1+1)");
        }
    }

    @Test
    void thePackageHasNoLinksMacrosConnectionsOrForeignNames() {
        for (ReportResult result : List.of(RenderFixtures.paA(), RenderFixtures.empresas())) {
            Map<String, String> parts = Workbooks.parts(render(result));
            for (Map.Entry<String, String> part : parts.entrySet()) {
                String name = part.getKey();
                assertThat(name.contains("externalLink") || name.contains("vbaProject")
                        || name.contains("connections") || name.contains("activeX") || name.contains("embeddings"))
                        .as(name).isFalse();
                if (name.endsWith(".rels")) {
                    assertThat(part.getValue()).doesNotContain("hyperlink");
                    assertThat(part.getValue()).doesNotContain("TargetMode=\"External\"");
                }
                if (name.startsWith("xl/worksheets/")) {
                    assertThat(part.getValue()).doesNotContain("<f>");
                    assertThat(part.getValue()).doesNotContain("<f ");
                    assertThat(part.getValue()).doesNotContain("<hyperlinks");
                    assertThat(part.getValue()).doesNotContain("<mergeCells");
                }
            }
            String workbook = parts.get("xl/workbook.xml");
            List<String> names = new ArrayList<>();
            Matcher matcher = Pattern.compile("<definedName [^>]*name=\"([^\"]+)\"[^>]*>([^<]*)</definedName>")
                    .matcher(workbook);
            while (matcher.find()) {
                names.add(matcher.group(1));
                // D-XL-5 (EX-19): besides the autofilter, only the print titles of a detail sheet, and only its
                // header row: a quoted reference to rows 1:1 of one sheet, never anything that computes.
                if (matcher.group(1).equals("_xlnm.Print_Titles")) {
                    assertThat(matcher.group(2)).matches("'([^']|'')+'!\\$1:\\$1");
                }
            }
            // One detail sheet per report: its autofilter and its print titles (D-XL-5); before EX-19 only the first.
            assertThat(names).containsExactly("_xlnm._FilterDatabase", "_xlnm.Print_Titles");
        }
    }

    @Test
    void thePrintTitlesOfAHostileSheetNameAreAQuotedReferenceOnly() {
        WorkbookLayout layout = new WorkbookLayout("=HYPERLINK(\"http://x\")", List.of(new SheetLayout("@SUM(1+1)'x",
                List.of(LayoutRow.of(new LayoutCell(0, CellValue.text("=1+1"), StyleKind.TABLE_HEADER)),
                        LayoutRow.of(new LayoutCell(0, CellValue.text("a"), StyleKind.DATA))), 1,
                new SheetLayout.FilterRange(0, 1, 0, 0))), "=A1 & {0} {1}");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            XlsxWriter.write(layout, java.time.Instant.parse("2026-11-01T18:00:03Z"), 10, out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Map<String, String> parts = Workbooks.parts(out.toByteArray());
        assertThat(parts.get("xl/workbook.xml")).contains(
                "<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">'@SUM(1+1)''x'!$1:$1</definedName>");
        // The footer is the layout's text with & escaped: never another header/footer code.
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("<oddFooter>&amp;C=A1 &amp;&amp; &amp;P &amp;N</oddFooter>");
        for (Map.Entry<String, String> part : parts.entrySet()) {
            if (part.getKey().startsWith("xl/worksheets/")) {
                assertThat(part.getValue()).doesNotContain("<f>").doesNotContain("<f ")
                        .doesNotContain("<conditionalFormatting").doesNotContain("<dataValidations")
                        .doesNotContain("<hyperlinks");
            }
        }
        // The title property is plain text: it never computes anything in Excel.
        assertThat(parts.get("docProps/core.xml")).contains("<dc:title>=HYPERLINK(\"http://x\")</dc:title>");
    }

    private static Cell find(XSSFWorkbook wb, String text) {
        for (Sheet s : wb) {
            for (Row r : s) {
                for (Cell c : r) {
                    if (c.getCellType() == CellType.STRING && c.getStringCellValue().equals(text)) {
                        return c;
                    }
                }
            }
        }
        throw new AssertionError("no cell " + text);
    }

    private static void assertNoFormula(XSSFWorkbook wb) {
        for (Sheet s : wb) {
            for (Row r : s) {
                for (Cell c : r) {
                    assertThat(c.getCellType() == CellType.FORMULA).as(c.getAddress().toString()).isFalse();
                }
            }
        }
    }
}
