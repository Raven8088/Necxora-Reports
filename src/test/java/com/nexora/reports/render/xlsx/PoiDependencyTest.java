package com.nexora.reports.render.xlsx;

import org.apache.poi.Version;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B1: Apache POI resolves at the reviewed version, and streaming writing (SXSSF) works end to end: rows beyond the
 * in-memory window are flushed to temporary files, the workbook is readable by XSSF, and the temporary files are
 * released. Excel layout rules (nexora-excel-report/1) are tested in a later block.
 */
class PoiDependencyTest {

    @Test
    void theReviewedPoiVersionIsTheOneResolved() {
        assertThat(Version.getVersion()).isEqualTo("5.4.0");
    }

    @Test
    void sxssfFlushesRowsWritesAReadableWorkbookAndReleasesTemporaryFiles() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean disposed;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(10)) {
            Sheet sheet = workbook.createSheet("Reporte");
            for (int i = 0; i < 25; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("fila " + i);
                row.createCell(1).setCellValue(i);
            }
            workbook.write(out);
            disposed = workbook.dispose();
        }
        assertThat(disposed).isTrue();

        try (XSSFWorkbook read = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = read.getSheet("Reporte");
            assertThat(sheet.getLastRowNum()).isEqualTo(24);
            assertThat(sheet.getRow(0).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(24).getCell(0).getStringCellValue()).isEqualTo("fila 24");
            assertThat(sheet.getRow(24).getCell(1).getNumericCellValue()).isEqualTo(24.0);
        }
    }
}
