package com.nexora.reports.render.xlsx;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.render.layout.CellValue;
import com.nexora.reports.render.layout.LayoutCell;
import com.nexora.reports.render.layout.LayoutRow;
import com.nexora.reports.render.layout.SheetLayout;
import com.nexora.reports.render.layout.StyleKind;
import com.nexora.reports.render.layout.WorkbookLayout;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.ReportResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.nexora.reports.plan2.ContractFixtures.m;

/**
 * Presentation of the Excel reports (D-XL-1): the workbooks whose grid ({@link GridSnapshot}) must not change. They
 * were written with the renderer BEFORE the presentation change and stored as {@code grid/<name>.grid}; the same
 * fixtures are written now and compared. Two levels:
 * <ul>
 *   <li>the whole renderer on the generic results of A4-B6: PA-A (grouped summary with its own total and a detail)
 *       in Spanish and English, partial, with a value kept as text and with texts cut by the result; «Empresas»
 *       (another schema, a null group key); a 300-row detail;</li>
 *   <li>the writer alone, with layouts that cover what the results above do not: a simple report (only a detail with
 *       every kind of value) and a composite one (two sections with summaries and totals, a warning, a detail and a
 *       methodology), and the layout of {@code XlsxWriterTest}.</li>
 * </ul>
 * Only APIs that existed before the change are used, so that this class compiles against both versions.
 */
public final class GridFixtures {

    static final Instant CREATED = Instant.parse("2026-11-01T18:00:03Z");

    private GridFixtures() {
    }

    public static Map<String, Supplier<byte[]>> all() {
        Map<String, Supplier<byte[]>> f = new LinkedHashMap<>();
        f.put("pa-a-es", () -> render(RenderFixtures.paA()));
        f.put("pa-a-en", () -> {
            ContractFixtures.PaA en = new ContractFixtures.PaA();
            m(en.spec.get("parameters")).put("labelLanguage", "en");
            en.reseal();
            return render(RenderFixtures.paA(en, RenderFixtures.paARows(), RenderFixtures.complete(3)));
        });
        f.put("pa-a-parcial", () -> render(RenderFixtures.truncatedPaA()));
        f.put("pa-a-texto-exacto", () -> {
            List<List<List<Object>>> data = RenderFixtures.paARows();
            data.set(1, List.of(List.of((1L << 53) + 1)));
            return render(RenderFixtures.paA(new ContractFixtures.PaA(), data, RenderFixtures.complete(3)));
        });
        f.put("pa-a-textos-recortados", () -> {
            ContractFixtures.PaA d = new ContractFixtures.PaA();
            return render(RenderFixtures.assemble(TestPlans.VALIDATOR.validate(d.tenantId, d.dataSourceId,
                    d.reportPlan, d.spec, d.plans), d.spec, RenderFixtures.paARows(), RenderFixtures.complete(3),
                    new AssemblyLimits(600_000, 8)));
        });
        f.put("empresas", () -> render(RenderFixtures.empresas()));
        f.put("pa-a-300-filas", () -> render(RenderFixtures.paA(new ContractFixtures.PaA(), rows(300),
                RenderFixtures.complete(3))));
        f.put("escritor-simple", () -> write(simple()));
        f.put("escritor-compuesto", () -> write(composite()));
        f.put("escritor-prueba", () -> write(XlsxWriterTest.layout()));
        return f;
    }

    public static byte[] render(ReportResult result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxRenderer().render(result, out, RenderLimits.DEFAULTS);
        return out.toByteArray();
    }

    static byte[] write(WorkbookLayout layout) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XlsxWriter.write(layout, CREATED, 50, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** PA-A with a detail of {@code n} synthetic rows (nulls every 7th row). */
    public static List<List<List<Object>>> rows(int n) {
        List<List<List<Object>>> data = RenderFixtures.paARows();
        List<List<Object>> detail = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            detail.add(Arrays.asList("Pozo-" + i, i % 7 == 0 ? null : "Campo " + (i % 13), "País " + (i % 5),
                    "Estado " + (i % 11), "activo", (long) i));
        }
        data.set(2, detail);
        return data;
    }

    private static LayoutCell c(int column, CellValue value, StyleKind style) {
        return new LayoutCell(column, value, style);
    }

    private static CellValue.Number n(double v, String format) {
        return new CellValue.Number(v, format);
    }

    private static CellValue.DateTime d(LocalDateTime v, String format) {
        return new CellValue.DateTime(v, format);
    }

    /** A simple report: one detail with every kind of value, a long header and a formula-like text. */
    static WorkbookLayout simple() {
        List<LayoutRow> rows = new ArrayList<>();
        rows.add(LayoutRow.of(c(0, CellValue.text("Código"), StyleKind.TABLE_HEADER),
                c(1, CellValue.text("Nombre completo del registro con un encabezado muy largo"), StyleKind.TABLE_HEADER),
                c(2, CellValue.text("Cantidad"), StyleKind.TABLE_HEADER),
                c(3, CellValue.text("Importe"), StyleKind.TABLE_HEADER),
                c(4, CellValue.text("Fecha"), StyleKind.TABLE_HEADER),
                c(5, CellValue.text("Registrado (UTC)"), StyleKind.TABLE_HEADER),
                c(6, CellValue.text("Activo"), StyleKind.TABLE_HEADER)));
        for (int i = 0; i < 25; i++) {
            List<LayoutCell> cells = new ArrayList<>();
            cells.add(c(0, CellValue.text(i == 3 ? "=Cuenca-9" : "C-" + (100 + i)), StyleKind.DATA));
            if (i % 5 != 0) {
                cells.add(c(1, CellValue.text("Elemento número " + i), StyleKind.DATA));
            }
            cells.add(c(2, n(i * 1_234L - 5_000, "#,##0"), StyleKind.DATA));
            cells.add(c(3, n(i * 1_234.567, "#,##0.000"), StyleKind.DATA));
            cells.add(c(4, d(LocalDateTime.of(2025, 1 + i % 12, 1 + i, 0, 0), "yyyy-mm-dd"), StyleKind.DATA));
            cells.add(c(5, d(LocalDateTime.of(2025, 6, 1, i % 24, i, 5), "yyyy-mm-dd hh:mm:ss"), StyleKind.DATA));
            cells.add(c(6, CellValue.text(i % 2 == 0 ? "Sí" : "No"), StyleKind.DATA));
            rows.add(new LayoutRow(cells));
        }
        return new WorkbookLayout("Simple", List.of(new SheetLayout("Detalle", rows, 1,
                new SheetLayout.FilterRange(0, 25, 0, 6))));
    }

    /** A composite report: header, a warning, two sections with summaries and totals, a detail and a methodology. */
    static WorkbookLayout composite() {
        List<LayoutRow> report = new ArrayList<>();
        report.add(LayoutRow.of(c(0, CellValue.text("Informe compuesto"), StyleKind.TITLE)));
        report.add(LayoutRow.EMPTY);
        report.add(LayoutRow.of(c(0, CellValue.text("Solicitud"), StyleKind.LABEL),
                c(1, CellValue.text("una solicitud muy larga ".repeat(8)), StyleKind.VALUE)));
        report.add(LayoutRow.of(c(0, CellValue.text("Estado"), StyleKind.LABEL),
                c(1, CellValue.text("Parcial"), StyleKind.VALUE)));
        report.add(LayoutRow.of(c(0, CellValue.text("Aviso"), StyleKind.WARNING),
                c(1, CellValue.text("El dataset «Detalle» se truncó a 10 filas."), StyleKind.WARNING)));
        for (int s = 1; s <= 2; s++) {
            report.add(LayoutRow.EMPTY);
            report.add(LayoutRow.of(c(0, CellValue.text("Sección " + s), StyleKind.SECTION_TITLE)));
            report.add(LayoutRow.EMPTY);
            report.add(LayoutRow.of(c(0, CellValue.text("Resumen " + s), StyleKind.ITEM_TITLE)));
            report.add(LayoutRow.of(c(0, CellValue.text("Grupo"), StyleKind.TABLE_HEADER),
                    c(1, CellValue.text("Subgrupo"), StyleKind.TABLE_HEADER),
                    c(2, CellValue.text("Registros"), StyleKind.TABLE_HEADER),
                    c(3, CellValue.text("Promedio"), StyleKind.TABLE_HEADER)));
            for (int g = 0; g < 4; g++) {
                report.add(LayoutRow.of(c(0, CellValue.text(g == 3 ? "(sin valor)" : "Grupo " + g), StyleKind.DATA),
                        c(1, CellValue.text("S" + g), StyleKind.DATA), c(2, n(10 * g + s, "#,##0"), StyleKind.DATA),
                        c(3, n(g * 3.25 + s, "#,##0.00"), StyleKind.DATA)));
            }
            report.add(LayoutRow.of(c(0, CellValue.text("Total general"), StyleKind.TOTAL_LABEL),
                    c(2, n(60 + 4 * s, "#,##0"), StyleKind.TOTAL_VALUE)));
        }
        report.add(LayoutRow.of(c(0, CellValue.text("Ver la hoja «Detalle» (10 filas)."), StyleKind.NOTE)));
        List<LayoutRow> detail = new ArrayList<>();
        detail.add(LayoutRow.of(c(0, CellValue.text("Grupo"), StyleKind.TABLE_HEADER),
                c(1, CellValue.text("Valor"), StyleKind.TABLE_HEADER)));
        for (int i = 0; i < 10; i++) {
            detail.add(LayoutRow.of(c(0, CellValue.text("Grupo " + (i % 4)), StyleKind.DATA),
                    c(1, n(i * 0.5, "#,##0.0"), StyleKind.DATA)));
        }
        List<LayoutRow> methodology = List.of(
                LayoutRow.of(c(0, CellValue.text("Ejecución"), StyleKind.SECTION_TITLE)),
                LayoutRow.of(c(0, CellValue.text("Huella"), StyleKind.LABEL),
                        c(1, CellValue.text("sha256:" + "a".repeat(64)), StyleKind.VALUE)),
                LayoutRow.of(c(0, CellValue.text("Dataset"), StyleKind.TABLE_HEADER),
                        c(1, CellValue.text("Filas"), StyleKind.TABLE_HEADER)),
                LayoutRow.of(c(0, CellValue.text("detalle"), StyleKind.VALUE), c(1, n(10, "#,##0"), StyleKind.VALUE)));
        return new WorkbookLayout("Informe compuesto", List.of(new SheetLayout("Reporte", report, 0, null),
                new SheetLayout("Detalle", detail, 1, new SheetLayout.FilterRange(0, 10, 0, 1)),
                new SheetLayout("Metodología", methodology, 0, null)));
    }
}
