package com.nexora.reports.render.xlsx;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Test-only: a written XLSX reopened with the full (non-streaming) POI model, and its package parts. Public since
 * B10-c.1, so that the API tests compare downloaded files with the same reader as the B7 acceptance.
 */
public final class Workbooks {

    private Workbooks() {
    }

    public static XSSFWorkbook open(byte[] bytes) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every row of {@code sheet} as its values by column: String, Double, LocalDateTime or null; empty rows too. */
    public static List<List<Object>> rows(Sheet sheet) {
        List<List<Object>> rows = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                rows.add(List.of());
                continue;
            }
            Object[] values = new Object[Math.max(0, row.getLastCellNum())];
            for (Cell c : row) {
                values[c.getColumnIndex()] = value(c);
            }
            rows.add(Arrays.asList(values));
        }
        return rows;
    }

    public static Object value(Cell c) {
        if (c.getCellType() == CellType.STRING) {
            return c.getStringCellValue();
        }
        if (c.getCellType() == CellType.NUMERIC) {
            return DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue() : c.getNumericCellValue();
        }
        if (c.getCellType() == CellType.BLANK) {
            return null;
        }
        throw new AssertionError("unexpected cell type " + c.getCellType() + " at " + c.getAddress());
    }

    /** The parts of the package, read through its central directory: name → content (as UTF-8 text). */
    public static Map<String, String> parts(byte[] bytes) {
        Map<String, String> parts = new LinkedHashMap<>();
        try {
            Path file = Files.createTempFile("b7-parts-", ".xlsx");
            try {
                Files.write(file, bytes);
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    for (ZipEntry e : Collections.list(zip.entries())) {
                        try (InputStream in = zip.getInputStream(e)) {
                            parts.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return parts;
    }
}
