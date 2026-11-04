package com.nexora.reports.render.xlsx;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.ReportResult;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Presentation of the Excel reports (EX-19, D-XL-1 to D-XL-8): the styles are applied automatically from the kind of
 * each cell and the category of its value, the same for any report (PA-A, «Empresas», a simple and a composite
 * layout), with no rule about a company, a column name or a question.
 */
class PresentationStyleTest {

    private static final String PRIMARY = PresentationTheme.DEFAULT.primaryColor();

    private static XSSFWorkbook open(byte[] bytes) {
        return Workbooks.open(bytes);
    }

    private static Cell cell(XSSFSheet sheet, String text) {
        for (Row r : sheet) {
            for (Cell c : r) {
                if (c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && c.getStringCellValue().equals(text)) {
                    return c;
                }
            }
        }
        throw new AssertionError("no cell «" + text + "» in " + sheet.getSheetName());
    }

    private static XSSFCellStyle style(Cell c) {
        return (XSSFCellStyle) c.getCellStyle();
    }

    private static XSSFFont font(Cell c) {
        return style(c).getFont();
    }

    private static String hex(XSSFColor color) {
        return color == null ? null : HexFormat.of().withUpperCase().formatHex(Arrays.copyOfRange(color.getRGB(), 0, 3));
    }

    private static String fill(Cell c) {
        return style(c).getFillPattern() == FillPatternType.SOLID_FOREGROUND
                ? hex(style(c).getFillForegroundXSSFColor()) : null;
    }

    @Test
    void theReportSheetHasAVisualHierarchyAndNoGridlines() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA()))) {
            XSSFSheet report = wb.getSheet("Reporte");
            Cell title = report.getRow(0).getCell(0);
            assertThat(font(title).getBold()).isTrue();
            assertThat(font(title).getFontHeightInPoints()).isEqualTo((short) 16);
            assertThat(hex(font(title).getXSSFColor())).isEqualTo(PRIMARY);
            Cell label = cell(report, "Solicitud");
            assertThat(font(label).getBold()).isTrue();
            assertThat(hex(font(label).getXSSFColor())).isEqualTo(XlsxStyles.LABEL_COLOR);
            Cell request = report.getRow(label.getRowIndex()).getCell(1);
            assertThat(font(request).getBold()).isFalse();
            Cell section = cell(report, "Pozos registrados");
            assertThat(style(section).getBorderBottom()).isEqualTo(BorderStyle.MEDIUM);
            assertThat(report.isDisplayGridlines()).isFalse();
            assertThat(wb.getSheet("Metodología").isDisplayGridlines()).isFalse();
            assertThat(wb.getSheet("Listado de pozos").isDisplayGridlines()).isTrue();
        }
    }

    @Test
    void tableHeadersDataAndTotalsAreStyledByTheirKindAndTheirValue() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA()))) {
            XSSFSheet report = wb.getSheet("Reporte");
            Cell header = cell(report, "País");
            assertThat(fill(header)).isEqualTo(PRIMARY);
            assertThat(hex(font(header).getXSSFColor())).isEqualTo("FFFFFF");
            assertThat(font(header).getBold()).isTrue();
            assertThat(style(header).getWrapText()).isTrue();
            assertThat(style(header).getAlignment()).isEqualTo(HorizontalAlignment.LEFT);
            Row headerRow = report.getRow(header.getRowIndex());
            Cell countHeader = headerRow.getCell(2);
            assertThat(style(countHeader).getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            Row first = report.getRow(header.getRowIndex() + 1);
            assertThat(style(first.getCell(0)).getAlignment()).isEqualTo(HorizontalAlignment.LEFT);
            assertThat(style(first.getCell(0)).getBorderTop()).isEqualTo(BorderStyle.THIN);
            assertThat(hex(style(first.getCell(0)).getTopBorderXSSFColor())).isEqualTo(XlsxStyles.GRID_COLOR);
            assertThat(style(first.getCell(2)).getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            assertThat(fill(first.getCell(0))).isNull();
            Cell total = cell(report, "Total general");
            Cell totalValue = report.getRow(total.getRowIndex()).getCell(2);
            for (Cell c : new Cell[] {total, totalValue}) {
                assertThat(font(c).getBold()).isTrue();
                assertThat(fill(c)).isEqualTo(PresentationTheme.DEFAULT.accentColor());
                assertThat(style(c).getBorderTop()).isEqualTo(BorderStyle.DOUBLE);
                assertThat(style(c).getBorderBottom()).isEqualTo(BorderStyle.MEDIUM);
            }
            assertThat(style(totalValue).getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            // The number format stays the layout's (EX-06, D-XL-4).
            assertThat(style(totalValue).getDataFormatString()).isEqualTo("#,##0");
            Cell note = cell(report, "Ver la hoja «Listado de pozos» (35 filas).");
            assertThat(font(note).getItalic()).isTrue();
            XSSFSheet detail = wb.getSheet("Listado de pozos");
            assertThat(fill(detail.getRow(0).getCell(0))).isEqualTo(PRIMARY);
            assertThat(style(detail.getRow(1).getCell(0)).getBorderLeft()).isEqualTo(BorderStyle.THIN);
            // Methodology tables: «Truncado» Sí/No is a boolean, centred; row counts are integers, right.
            XSSFSheet methodology = wb.getSheet("Metodología");
            Cell truncated = cell(methodology, "No");
            assertThat(style(truncated).getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
            assertThat(style(methodology.getRow(truncated.getRowIndex()).getCell(1)).getAlignment())
                    .isEqualTo(HorizontalAlignment.RIGHT);
        }
    }

    @Test
    void aNullGroupKeyIsShownApartAndTheRestOfAnotherSchemaByTheSameRules() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.empresas()))) {
            XSSFSheet report = wb.getSheet("Reporte");
            Cell noValue = cell(report, "(sin valor)");
            assertThat(font(noValue).getItalic()).isTrue();
            assertThat(hex(font(noValue).getXSSFColor())).isEqualTo(XlsxStyles.NO_VALUE_COLOR);
            Cell other = report.getRow(noValue.getRowIndex() - 1).getCell(0);
            assertThat(font(other).getItalic()).isFalse();
            assertThat(style(report.getRow(noValue.getRowIndex()).getCell(1)).getAlignment())
                    .isEqualTo(HorizontalAlignment.RIGHT);
        }
    }

    @Test
    void aPartialReportAndItsWarningsAreClearlyMarked() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.truncatedPaA()))) {
            XSSFSheet report = wb.getSheet("Reporte");
            Cell status = cell(report, "Parcial");
            assertThat(fill(status)).isEqualTo(XlsxStyles.WARNING_FILL);
            assertThat(font(status).getBold()).isTrue();
            Cell warning = cell(report, "Aviso");
            assertThat(fill(warning)).isEqualTo(XlsxStyles.WARNING_FILL);
            assertThat(fill(report.getRow(warning.getRowIndex()).getCell(1))).isEqualTo(XlsxStyles.WARNING_FILL);
        }
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA()))) {
            assertThat(fill(cell(wb.getSheet("Reporte"), "Completo"))).isNull();
        }
    }

    @Test
    void everyKindOfValueOfASimpleReportIsAlignedByItsType() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.write(GridFixtures.simple()))) {
            XSSFSheet sheet = wb.getSheet("Detalle");
            Row row = sheet.getRow(2);
            assertThat(style(row.getCell(0)).getAlignment()).isEqualTo(HorizontalAlignment.LEFT);
            assertThat(style(row.getCell(2)).getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            assertThat(style(row.getCell(3)).getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            assertThat(style(row.getCell(4)).getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
            assertThat(style(row.getCell(5)).getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
            // A formula-like text keeps its quote prefix whatever its style (EX-11).
            assertThat(style(sheet.getRow(4).getCell(0)).getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void aLongRequestNoLongerWidensTheColumnsOfTheTables() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA()))) {
            XSSFSheet report = wb.getSheet("Reporte");
            String request = report.getRow(cell(report, "Solicitud").getRowIndex()).getCell(1).getStringCellValue();
            assertThat(request.length()).isGreaterThan(100);
            // Column B holds the «Campo» of the summary: before EX-19 the request made it 60 characters wide.
            assertThat(report.getColumnWidth(1)).isLessThan(30 * 256);
            assertThat(report.getColumnWidth(1)).isGreaterThanOrEqualTo(XlsxWriter.MIN_WIDTH * 256);
        }
    }

    @Test
    void longHeadersWrapInsteadOfWideningTheirColumn() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.write(GridFixtures.simple()))) {
            XSSFSheet sheet = wb.getSheet("Detalle");
            int length = "Nombre completo del registro con un encabezado muy largo".length();
            assertThat(sheet.getColumnWidth(1)).isLessThan((length + 2) * 256);
            assertThat(sheet.getColumnWidth(1)).isEqualTo((XlsxWriter.headerWidth(length)
                    + XlsxWriter.FILTER_BUTTON + 2) * 256);
            assertThat(sheet.getRow(0).getHeightInPoints()).isGreaterThan(sheet.getDefaultRowHeightInPoints());
            assertThat(style(sheet.getRow(0).getCell(1)).getWrapText()).isTrue();
            assertThat(sheet.getRow(1).getHeightInPoints()).isEqualTo(sheet.getDefaultRowHeightInPoints());
        }
        assertThat(XlsxWriter.headerWidth(10)).isEqualTo(10);
        assertThat(XlsxWriter.headerWidth(30)).isEqualTo(XlsxWriter.HEADER_WRAP);
        assertThat(XlsxWriter.headerWidth(90)).isEqualTo(30);
        assertThat(XlsxWriter.headerWidth(1000)).isEqualTo(XlsxWriter.MAX_WIDTH - 2);
        assertThat(XlsxWriter.numberWidth(1234.5, "#,##0.00")).isEqualTo(8);
        assertThat(XlsxWriter.numberWidth(-1_234_567, "#,##0")).isEqualTo(10);
        assertThat(XlsxWriter.numberWidth(0, "#,##0")).isEqualTo(1);
        assertThat(XlsxWriter.numberWidth(1e20, "#,##0")).isEqualTo(27);
    }

    @Test
    void filtersAndFrozenRowsAreKeptAndDetailsPrintWithTheirHeaderOnEveryPage() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA()))) {
            XSSFSheet detail = wb.getSheet("Listado de pozos");
            assertThat(detail.getPaneInformation().isFreezePane()).isTrue();
            assertThat(detail.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:E36");
            assertThat(detail.getRepeatingRows().formatAsString()).isEqualTo("1:1");
            assertThat(detail.getFitToPage()).isTrue();
            assertThat(detail.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
            assertThat(detail.getPrintSetup().getFitHeight()).isEqualTo((short) 0);
            assertThat(detail.getPrintSetup().getLandscape()).isFalse();
            assertThat(detail.getFooter().getCenter()).isEqualTo("Página &P de &N");
            XSSFSheet report = wb.getSheet("Reporte");
            assertThat(report.getRepeatingRows()).isNull();
            // Not fitted: its long texts (the request) run over empty cells and would shrink the whole page.
            assertThat(report.getFitToPage()).isFalse();
            assertThat(wb.getSheet("Metodología").getFitToPage()).isFalse();
            assertThat(report.getFooter().getCenter()).isEqualTo("Página &P de &N");
        }
        ContractFixtures.PaA en = new ContractFixtures.PaA();
        m(en.spec.get("parameters")).put("labelLanguage", "en");
        en.reseal();
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA(en, RenderFixtures.paARows(),
                RenderFixtures.complete(3))))) {
            assertThat(wb.getSheet("Reporte").getFooter().getCenter()).isEqualTo("Page &P of &N");
        }
        try (XSSFWorkbook wb = open(GridFixtures.write(GridFixtures.simple()))) {
            assertThat(wb.getSheet("Detalle").getPrintSetup().getLandscape()).isTrue();
        }
    }

    @Test
    void theTitleIsAPropertyOfTheFileButTheRequestIsNot() throws IOException {
        byte[] bytes = GridFixtures.render(RenderFixtures.paA());
        try (XSSFWorkbook wb = open(bytes)) {
            var core = wb.getProperties().getCoreProperties();
            assertThat(core.getTitle()).isEqualTo("Reporte de pozos registrados");
            assertThat(core.getCreator()).isEqualTo("Nexora AI");
        }
        String request = "Genera un reporte de los pozos registrados";
        for (Map.Entry<String, String> part : Workbooks.parts(bytes).entrySet()) {
            if (part.getKey().startsWith("docProps/")) {
                assertThat(part.getValue()).as(part.getKey()).doesNotContain(request);
            }
        }
        assertThat(XlsxWriter.titleProperty("  a\u0000b\tc\n ")).isEqualTo("a b c");
        assertThat(XlsxWriter.titleProperty("x".repeat(300))).hasSize(XlsxWriter.MAX_TITLE_PROPERTY);
        assertThat(XlsxWriter.titleProperty("x".repeat(254) + "😀")).hasSize(254);
        assertThat(XlsxWriter.footer("A & B {0}/{1}\u0007")).isEqualTo("A && B &P/&N ");
    }

    @Test
    void theNumberOfStylesDoesNotGrowWithTheRows() throws IOException {
        int small = styles(GridFixtures.render(RenderFixtures.paA(new ContractFixtures.PaA(), GridFixtures.rows(300),
                RenderFixtures.complete(3))));
        int large = styles(GridFixtures.render(RenderFixtures.paA(new ContractFixtures.PaA(), GridFixtures.rows(5_000),
                RenderFixtures.complete(3))));
        assertThat(large).isEqualTo(small);
        assertThat(styles(GridFixtures.render(RenderFixtures.paA()))).isLessThan(60);
        int composite = styles(GridFixtures.write(GridFixtures.composite()));
        assertThat(composite).isLessThan(60);
    }

    private static int styles(byte[] bytes) {
        try (XSSFWorkbook wb = open(bytes)) {
            return wb.getNumCellStyles();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void bandsAreOffByDefaultAndFollowTheThemeWhenOn() throws IOException {
        try (XSSFWorkbook wb = open(GridFixtures.render(RenderFixtures.paA()))) {
            XSSFSheet detail = wb.getSheet("Listado de pozos");
            assertThat(fill(detail.getRow(2).getCell(0))).isNull();
        }
        PresentationTheme banded = PresentationTheme.of(null, null, null, "EAF1FB", null, null, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxRenderer(1, null, banded).render(RenderFixtures.paA(), out, RenderLimits.DEFAULTS);
        try (XSSFWorkbook wb = open(out.toByteArray())) {
            XSSFSheet detail = wb.getSheet("Listado de pozos");
            assertThat(fill(detail.getRow(2).getCell(0))).isEqualTo("EAF1FB");
            assertThat(fill(detail.getRow(1).getCell(0))).isNull();
            assertThat(fill(detail.getRow(0).getCell(0))).isEqualTo(PRIMARY);
        }
        assertThat(GridSnapshot.of(out.toByteArray())).isEqualTo(GridSnapshot.of(GridFixtures.render(
                RenderFixtures.paA())));
    }

    @Test
    void anotherThemeChangesTheLookButNotTheGrid() throws IOException {
        PresentationTheme theme = PresentationTheme.of("#7b2d26", "ffffff", "F8E5E3", null, "Arial", 10, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReportResult result = RenderFixtures.empresas();
        new XlsxRenderer(1, null, theme).render(result, out, RenderLimits.DEFAULTS);
        try (XSSFWorkbook wb = open(out.toByteArray())) {
            Cell header = cell(wb.getSheet("Reporte"), "Operadora");
            assertThat(fill(header)).isEqualTo("7B2D26");
            assertThat(font(header).getFontName()).isEqualTo("Arial");
            assertThat(font(header).getFontHeightInPoints()).isEqualTo((short) 10);
        }
        assertThat(GridSnapshot.of(out.toByteArray())).isEqualTo(GridSnapshot.of(GridFixtures.render(result)));
    }

    @Test
    void thePresentationIsDeterministic() throws IOException {
        // EX-18: the same result gives the same styles, widths and print settings (every part but the zip times).
        Map<String, String> first = Workbooks.parts(GridFixtures.render(RenderFixtures.paA()));
        Map<String, String> second = Workbooks.parts(GridFixtures.render(RenderFixtures.paA()));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aLargeDetailKeepsStreamingThroughASmallWindow() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxRenderer().render(RenderFixtures.paA(new ContractFixtures.PaA(), GridFixtures.rows(10_000),
                RenderFixtures.complete(3)), out, new RenderLimits(RenderLimits.DEFAULT_MAX_FILE_BYTES, 50,
                Duration.ofSeconds(1)));
        try (XSSFWorkbook wb = open(out.toByteArray())) {
            XSSFSheet detail = wb.getSheet("Listado de pozos");
            assertThat(detail.getLastRowNum()).isEqualTo(10_000);
            assertThat(style(detail.getRow(10_000).getCell(0)).getBorderBottom()).isEqualTo(BorderStyle.THIN);
            assertThat(detail.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:E10001");
        }
    }
}
