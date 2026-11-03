package com.nexora.reports.render.xlsx;

import com.nexora.reports.render.layout.CellValue;
import com.nexora.reports.render.layout.LayoutCell;
import com.nexora.reports.render.layout.LayoutRow;
import com.nexora.reports.render.layout.SheetLayout;
import com.nexora.reports.render.layout.StyleKind;
import com.nexora.reports.render.layout.WorkbookLayout;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B7 (D-B7-3, EX-06, EX-11, EX-13; precision 8): the POI writing of a layout, every kind of cell. */
class XlsxWriterTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T18:00:03Z");
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 1, 8, 30, 5, 120_000_000);

    private static LayoutCell cell(int column, CellValue value, StyleKind style) {
        return new LayoutCell(column, value, style);
    }

    static WorkbookLayout layout() {
        return new WorkbookLayout("Prueba", List.of(
                new SheetLayout("Datos", List.of(
                        LayoutRow.of(cell(0, CellValue.text("Nombre"), StyleKind.TABLE_HEADER),
                                cell(1, CellValue.text("Valor"), StyleKind.TABLE_HEADER),
                                cell(2, CellValue.text("Fecha"), StyleKind.TABLE_HEADER)),
                        LayoutRow.of(cell(0, CellValue.text("=1+1"), StyleKind.DATA),
                                cell(1, new CellValue.Number(1234.5, "#,##0.00"), StyleKind.DATA),
                                cell(2, new CellValue.DateTime(WHEN, "yyyy-mm-dd hh:mm:ss.000"), StyleKind.DATA)),
                        LayoutRow.EMPTY,
                        LayoutRow.of(cell(0, CellValue.text("x".repeat(200)), StyleKind.DATA),
                                cell(1, CellValue.BLANK, StyleKind.DATA),
                                cell(3, new CellValue.Number(7, "#,##0"), StyleKind.DATA))),
                        1, new SheetLayout.FilterRange(0, 1, 0, 2)),
                new SheetLayout("Otra", List.of(
                        LayoutRow.of(cell(0, CellValue.text("Un título largo que ocupa varias columnas vacías"),
                                StyleKind.TITLE)),
                        LayoutRow.of(cell(0, CellValue.text("a"), StyleKind.VALUE))), 0, null)));
    }

    private static byte[] write(WorkbookLayout layout) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long cells = XlsxWriter.write(layout, CREATED, 2, out);
        assertThat(cells).isEqualTo(layout.cellCount());
        return out.toByteArray();
    }

    @Test
    void everyKindOfCellIsWrittenWithItsTypeAndFormat() throws IOException {
        byte[] bytes = write(layout());
        assertThat(layout().cellCount()).isEqualTo(10L);
        try (XSSFWorkbook wb = Workbooks.open(bytes)) {
            XSSFSheet sheet = wb.getSheet("Datos");
            assertThat(Workbooks.rows(sheet).get(0)).containsExactly("Nombre", "Valor", "Fecha");
            assertThat(wb.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            XSSFCell formula = sheet.getRow(1).getCell(0);
            assertThat(formula.getCellType()).isEqualTo(CellType.STRING);
            assertThat(formula.getStringCellValue()).isEqualTo("=1+1");
            assertThat(formula.getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(wb.getFontAt(formula.getCellStyle().getFontIndex()).getBold()).isFalse();
            XSSFCell number = sheet.getRow(1).getCell(1);
            assertThat(number.getNumericCellValue()).isEqualTo(1234.5);
            assertThat(number.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(number.getCellStyle().getQuotePrefixed()).isFalse();
            XSSFCell date = sheet.getRow(1).getCell(2);
            assertThat(date.getLocalDateTimeCellValue()).isEqualTo(WHEN);
            assertThat(date.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss.000");
            // XSSFRow is both Iterable<Cell> and Comparable<XSSFRow>: the cast selects AssertJ's object assertion.
            assertThat((Object) sheet.getRow(2)).isNull();
            assertThat(sheet.getRow(3).getCell(1)).isNull();
            assertThat(sheet.getRow(3).getCell(3).getNumericCellValue()).isEqualTo(7.0);
        }
    }

    @Test
    void theHeaderIsFrozenTheFilterSetAndTheWidthsBounded() throws IOException {
        try (XSSFWorkbook wb = Workbooks.open(write(layout()))) {
            XSSFSheet sheet = wb.getSheet("Datos");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat((int) sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo(1);
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:C2");
            assertThat(sheet.getNumMergedRegions()).isEqualTo(0);
            assertThat(sheet.getColumnWidth(0)).isEqualTo(XlsxWriter.MAX_WIDTH * 256);
            assertThat(sheet.getColumnWidth(1)).isEqualTo(18 * 256);
            assertThat(sheet.getColumnWidth(2)).isEqualTo(25 * 256);
            XSSFSheet other = wb.getSheet("Otra");
            assertThat(other.getPaneInformation()).isNull();
            assertThat(other.getCTWorksheet().isSetAutoFilter()).isFalse();
            assertThat(other.getColumnWidth(0)).isEqualTo(XlsxWriter.MIN_WIDTH * 256);
            assertThat(wb.getFontAt(other.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
        }
    }

    @Test
    void thePropertiesAreTheCreatorAndTheGenerationTime() throws IOException {
        try (XSSFWorkbook wb = Workbooks.open(write(layout()))) {
            var core = wb.getProperties().getCoreProperties();
            assertThat(core.getCreator()).isEqualTo("Nexora AI");
            assertThat(core.getCreated()).isEqualTo(Date.from(CREATED));
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetName(0)).isEqualTo("Datos");
        }
    }

    @Test
    void theStreamingTemporaryFilesAreRemovedAfterWritingAndAfterAFailure() throws IOException {
        int before = poiTemporaryFiles();
        write(layout());
        assertThat(poiTemporaryFiles()).isEqualTo(before);
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };
        assertThatThrownBy(() -> XlsxWriter.write(layout(), CREATED, 2, failing)).isInstanceOf(IOException.class);
        assertThat(poiTemporaryFiles()).isEqualTo(before);
    }

    @Test
    void thePackageCanBeReadSequentiallyWithoutZip64Records() throws IOException {
        byte[] bytes = write(layout());
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                zip.readAllBytes();
                names.add(e.getName());
            }
        }
        assertThat(names.contains("xl/workbook.xml")).isTrue();
        assertThat(names.contains("xl/worksheets/sheet1.xml")).isTrue();
        for (int i = 0; i + 4 <= bytes.length; i++) {
            boolean zip64EndRecord = bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 6 && bytes[i + 3] == 6;
            assertThat(zip64EndRecord).isFalse();
        }
    }

    static int poiTemporaryFiles() {
        // A4-B9: wherever Reports pointed POI (its storage directory), or POI's default before that.
        File[] files = PoiTemporaryFiles.directory().toFile().listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File f : files) {
            if (f.getName().startsWith("poi-sxssf")) {
                n++;
            }
        }
        return n;
    }
}
