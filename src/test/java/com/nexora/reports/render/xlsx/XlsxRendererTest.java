package com.nexora.reports.render.xlsx;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.render.layout.LayoutBuilder;
import com.nexora.reports.result.domain.PresentationException;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B7 (D-B7-1 to D-B7-6; EX-01 to EX-18; precisions 3 to 8): the Excel of the generic result, reopened with the
 * full POI model and checked against the contract, and the guarantees of the renderer: size, reserve, temporary
 * files, concurrency, digest.
 */
class XlsxRendererTest {

    private record Rendered(RenderedArtifact artifact, byte[] bytes) {
    }

    private static Rendered render(ReportResult result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderedArtifact artifact = new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
        return new Rendered(artifact, out.toByteArray());
    }

    @Test
    void paAFollowsTheExcelContract() throws IOException {
        Rendered r = render(RenderFixtures.paA());
        Map<String, Object> expected = RenderFixtures.paAExpected();
        try (XSSFWorkbook wb = Workbooks.open(r.bytes())) {
            assertThat(List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2)))
                    .containsExactly("Reporte", "Listado de pozos", "Metodología");
            assertThat(r.artifact().sheets()).containsExactly("Reporte", "Listado de pozos", "Metodología");
            List<List<Object>> report = Workbooks.rows(wb.getSheet("Reporte"));
            assertThat(report.get(2)).containsExactly("Título", "Reporte de pozos registrados");
            assertThat(report.get(10)).containsExactly("Estado", "Completo");
            int header = indexOf(report, "País");
            List<Object> summary = l(expected.get("resumen"));
            for (int i = 0; i < summary.size(); i++) {
                Map<String, Object> row = m(summary.get(i));
                assertThat(report.get(header + 1 + i)).isEqualTo(Arrays.asList(orNoValue(row.get("pais")),
                        orNoValue(row.get("campo")), ((Long) row.get("pozos")).doubleValue()));
            }
            assertThat(report.get(header + 1 + summary.size())).isEqualTo(Arrays.asList("Total general", null,
                    35.0));
            XSSFSheet detail = wb.getSheet("Listado de pozos");
            List<List<Object>> rows = Workbooks.rows(detail);
            assertThat(rows.getFirst()).containsExactly("Nombre", "Campo", "País", "Estado o región", "Estatus");
            assertThat(rows.size()).isEqualTo(36);
            List<Object> names = new ArrayList<>();
            for (Object row : l(expected.get("detalle"))) {
                names.add(m(row).get("nombre"));
            }
            assertThat(rows.subList(1, 36).stream().map(List::getFirst).toList()).isEqualTo(names);
            assertThat(detail.getPaneInformation().isFreezePane()).isTrue();
            assertThat(detail.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:E36");
            boolean bold = wb.getFontAt(detail.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold();
            assertThat(bold).isTrue();
            for (Sheet s : wb) {
                assertThat(s.getNumMergedRegions()).isEqualTo(0);
                assertThat(s.getSheetName().length() <= 31).isTrue();
                for (int c = 0; c < 8; c++) {
                    assertThat(s.getColumnWidth(c) <= XlsxWriter.MAX_WIDTH * 256).isTrue();
                }
            }
        }
    }

    @Test
    void theSecondReportIsWrittenByTheSameClasses() throws IOException {
        Rendered r = render(RenderFixtures.empresas());
        try (XSSFWorkbook wb = Workbooks.open(r.bytes())) {
            List<List<Object>> report = Workbooks.rows(wb.getSheet("Reporte"));
            int header = indexOf(report, "Operadora");
            assertThat(report.get(header + 3)).isEqualTo(List.of("(sin valor)", 1.0));
            assertThat(report.get(header + 4)).isEqualTo(List.of("Total general", 5.0));
            List<List<Object>> detail = Workbooks.rows(wb.getSheet("Detalle de empresas"));
            assertThat(detail.get(1)).isEqualTo(List.of("Grupo Petrolero Atlas", "GPA"));
            assertThat(detail.get(2)).isEqualTo(List.of("Servicios GPA", "GPA-SER", 10.0));
            assertThat(wb.getSheet("Detalle de empresas").getRow(1).getCell(2)).isNull();
        }
    }

    @Test
    void theArtifactDescribesExactlyTheBytesDelivered() throws NoSuchAlgorithmException {
        Rendered r = render(RenderFixtures.paA());
        assertThat(r.artifact().sizeBytes()).isEqualTo((long) r.bytes().length);
        assertThat(r.artifact().sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(r.bytes())));
        assertThat(r.artifact().mediaType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(r.artifact().excelContract()).isEqualTo("nexora-excel-report/1");
        assertThat(r.bytes()[0] == 'P' && r.bytes()[1] == 'K').isTrue();
    }

    @Test
    void theDataCellsAreDeterministic() throws IOException {
        Rendered first = render(RenderFixtures.paA());
        Rendered second = render(RenderFixtures.paA());
        try (XSSFWorkbook a = Workbooks.open(first.bytes()); XSSFWorkbook b = Workbooks.open(second.bytes())) {
            for (int i = 0; i < a.getNumberOfSheets(); i++) {
                assertThat(Workbooks.rows(b.getSheetAt(i))).isEqualTo(Workbooks.rows(a.getSheetAt(i)));
            }
        }
    }

    @Test
    void aPartialReportIsMarkedPartial() throws IOException {
        Rendered r = render(RenderFixtures.truncatedPaA());
        try (XSSFWorkbook wb = Workbooks.open(r.bytes())) {
            List<List<Object>> report = Workbooks.rows(wb.getSheet("Reporte"));
            assertThat(report.get(indexOf(report, "Estado"))).containsExactly("Estado", "Parcial");
            assertThat((String) report.get(indexOf(report, "Aviso")).get(1)).contains("entregó 3 filas");
            assertThat(Workbooks.rows(wb.getSheet("Listado de pozos")).size()).isEqualTo(4);
        }
    }

    @Test
    void anEnglishReportKeepsTheSheetNamesOfTheContract() throws IOException {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        m(documents.spec.get("parameters")).put("labelLanguage", "en");
        documents.reseal();
        Rendered r = render(RenderFixtures.paA(documents, RenderFixtures.paARows(), RenderFixtures.complete(3)));
        try (XSSFWorkbook wb = Workbooks.open(r.bytes())) {
            assertThat(wb.getSheetName(0)).isEqualTo("Reporte");
            assertThat(wb.getSheetName(2)).isEqualTo("Metodología");
            assertThat(Workbooks.rows(wb.getSheetAt(0)).get(2)).containsExactly("Title",
                    "Reporte de pozos registrados");
        }
    }

    @Test
    void aLargeDetailIsStreamedThroughASmallWindow() throws IOException {
        List<List<List<Object>>> data = RenderFixtures.paARows();
        List<List<Object>> detail = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            detail.add(Arrays.asList("Pozo-" + i, i % 7 == 0 ? null : "Campo " + (i % 13), "País " + (i % 5),
                    "Estado " + (i % 11), "activo", (long) i));
        }
        data.set(2, detail);
        ReportResult result = RenderFixtures.paA(new ContractFixtures.PaA(), data, RenderFixtures.complete(3));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderedArtifact artifact = new XlsxRenderer().render(result, out,
                new RenderLimits(RenderLimits.DEFAULT_MAX_FILE_BYTES, 50, Duration.ofSeconds(1)));
        assertThat(artifact.sizeBytes() <= RenderLimits.DEFAULT_MAX_FILE_BYTES).isTrue();
        try (XSSFWorkbook wb = Workbooks.open(out.toByteArray())) {
            XSSFSheet sheet = wb.getSheet("Listado de pozos");
            assertThat(sheet.getLastRowNum()).isEqualTo(10_000);
            assertThat(Workbooks.rows(sheet).get(10_000)).isEqualTo(Arrays.asList("Pozo-9999", "Campo 2", "País 4",
                    "Estado 0", "activo"));
            assertThat(sheet.getRow(8).getCell(1)).isNull();
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:E10001");
        }
    }

    @Test
    void aFileThatGrowsOverTheLimitIsDiscardedAndNothingIsDelivered() throws IOException {
        Path dir = Files.createTempDirectory("b7-render");
        try {
            ReportResult result = RenderFixtures.paA();
            long minimum = LayoutBuilder.build(result).minimumBytes();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RenderLimits limits = new RenderLimits(4_000, 200, Duration.ofSeconds(1));
            assertThat(minimum < limits.maxFileBytes()).isTrue();
            Throwable t = catchThrowable(() -> new XlsxRenderer(1, dir).render(result, out, limits));
            assertThat(t).isInstanceOf(PresentationException.class);
            assertThat(((PresentationException) t).code()).isEqualTo(PresentationException.Code.ARTIFACT_TOO_LARGE);
            assertThat(out.size()).isEqualTo(0);
            assertThat(files(dir)).isEqualTo(0L);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void aFileWhoseLowerBoundIsOverTheLimitIsRefusedBeforeWriting() throws IOException {
        Path dir = Files.createTempDirectory("b7-render");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Throwable t = catchThrowable(() -> new XlsxRenderer(1, dir).render(RenderFixtures.paA(), out,
                    new RenderLimits(10, 200, Duration.ofSeconds(1))));
            assertThat(((PresentationException) t).code()).isEqualTo(PresentationException.Code.ARTIFACT_TOO_LARGE);
            assertThat(t.getMessage()).startsWith("at least ");
            assertThat(out.size()).isEqualTo(0);
            assertThat(files(dir)).isEqualTo(0L);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void theTemporaryFileIsDeletedAfterSuccessAndAfterAFailure() throws IOException {
        Path dir = Files.createTempDirectory("b7-render");
        try {
            XlsxRenderer renderer = new XlsxRenderer(1, dir);
            int poiBefore = XlsxWriterTest.poiTemporaryFiles();
            renderer.render(RenderFixtures.paA(), new ByteArrayOutputStream(), RenderLimits.DEFAULTS);
            assertThat(files(dir)).isEqualTo(0L);
            OutputStream failing = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("client gone");
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("client gone");
                }
            };
            Throwable t = catchThrowable(() -> renderer.render(RenderFixtures.paA(), failing, RenderLimits.DEFAULTS));
            assertThat(((PresentationException) t).code()).isEqualTo(PresentationException.Code.RENDER_FAILED);
            assertThat(files(dir)).isEqualTo(0L);
            assertThat(XlsxWriterTest.poiTemporaryFiles()).isEqualTo(poiBefore);
            renderer.render(RenderFixtures.empresas(), new ByteArrayOutputStream(), RenderLimits.DEFAULTS);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void aLayoutOverTheReserveOfTheResultIsRefused() {
        ReportResult r = RenderFixtures.paA();
        ReportResult tight = new ReportResult(r.run(), r.header(), r.status(), r.datasets(), r.sections(),
                r.methodology(), r.warnings(), 10);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Throwable t = catchThrowable(() -> new XlsxRenderer().render(tight, out, RenderLimits.DEFAULTS));
        assertThat(((PresentationException) t).code()).isEqualTo(PresentationException.Code.RENDER_FAILED);
        assertThat(t.getMessage()).contains("over the reserve of 10");
        assertThat(out.size()).isEqualTo(0);
    }

    @Test
    void presentationsBeyondTheConcurrencyLimitAreRefused() {
        XlsxRenderer renderer = new XlsxRenderer(1, null);
        RenderLimits noWait = new RenderLimits(RenderLimits.DEFAULT_MAX_FILE_BYTES, 200, Duration.ZERO);
        AtomicReference<Throwable> inner = new AtomicReference<>();
        OutputStream reentrant = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (inner.get() == null) {
                    inner.set(catchThrowable(() -> renderer.render(RenderFixtures.empresas(),
                            new ByteArrayOutputStream(), noWait)));
                }
            }
        };
        renderer.render(RenderFixtures.paA(), reentrant, noWait);
        assertThat(inner.get()).isInstanceOf(PresentationException.class);
        assertThat(((PresentationException) inner.get()).code())
                .isEqualTo(PresentationException.Code.TOO_MANY_PRESENTATIONS);
        assertThat(((PresentationException) inner.get()).code().refusalCode()).isEqualTo("too_many_executions");
        renderer.render(RenderFixtures.empresas(), new ByteArrayOutputStream(), noWait);
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThatThrownBy(() -> new XlsxRenderer(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new XlsxRenderer().render(null, new ByteArrayOutputStream(), RenderLimits.DEFAULTS))
                .isInstanceOf(NullPointerException.class);
    }

    private static int indexOf(List<List<Object>> rows, Object first) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && first.equals(rows.get(i).getFirst())) {
                return i;
            }
        }
        throw new AssertionError("no row starting with " + first);
    }

    private static Object orNoValue(Object value) {
        return value == null ? "(sin valor)" : value;
    }

    private static long files(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }
}
