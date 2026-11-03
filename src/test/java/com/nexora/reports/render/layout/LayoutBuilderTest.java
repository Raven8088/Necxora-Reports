package com.nexora.reports.render.layout;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.ReportResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.render.LayoutAccess.find;
import static com.nexora.reports.render.LayoutAccess.rows;
import static com.nexora.reports.render.LayoutAccess.sheet;
import static com.nexora.reports.render.LayoutAccess.valueOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B7 (D-B7-1, D-B7-2; EX-01 to EX-16): the layout of the generic result, for PA-A and for the structurally
 * different «Empresas» report, by the same code and with no knowledge of either.
 */
class LayoutBuilderTest {

    @Test
    void theSheetsFollowTheContract() {
        assertThat(LayoutBuilder.build(RenderFixtures.paA()).sheetNames())
                .containsExactly("Reporte", "Listado de pozos", "Metodología");
        assertThat(LayoutBuilder.build(RenderFixtures.empresas()).sheetNames())
                .containsExactly("Reporte", "Detalle de empresas", "Metodología");
    }

    @Test
    void theHeaderHasEveryFieldInOrder() {
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(RenderFixtures.paA()), "Reporte"));
        assertThat(rows.getFirst()).containsExactly("Reporte de pozos registrados");
        List<Object> labels = new ArrayList<>();
        for (int i = 2; i <= 10; i++) {
            labels.add(rows.get(i).getFirst());
        }
        assertThat(labels).containsExactly("Título", "Solicitud", "Fuente", "Tenant", "Generado", "Filtros",
                "Periodo", "Versión de Nexora", "Estado");
        assertThat(valueOf(rows, "Generado")).isEqualTo("2026-11-01 12:00:03 (America/Mexico_City)");
        assertThat(valueOf(rows, "Filtros")).isEqualTo("Sin filtros");
        assertThat(valueOf(rows, "Periodo")).isEqualTo("No aplica");
        assertThat(valueOf(rows, "Versión de Nexora")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(valueOf(rows, "Estado")).isEqualTo("Completo");
        assertThat((String) valueOf(rows, "Tenant")).contains("No disponible");
        assertThat((String) valueOf(rows, "Fuente")).startsWith("Banco sintético (");
    }

    @Test
    void theSummaryHasOneRowPerGroupAndTheTotalOfItsOwnDataset() {
        Map<String, Object> expected = RenderFixtures.paAExpected();
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(RenderFixtures.paA()), "Reporte"));
        int header = find(rows, "País");
        assertThat(rows.get(header)).containsExactly("País", "Campo", "Pozos");
        assertThat(rows.get(header - 1)).containsExactly("Pozos por país y campo");
        List<Object> summary = l(expected.get("resumen"));
        for (int i = 0; i < summary.size(); i++) {
            Map<String, Object> r = m(summary.get(i));
            assertThat(rows.get(header + 1 + i)).isEqualTo(Arrays.asList(noValue(r.get("pais")),
                    noValue(r.get("campo")), ((Long) r.get("pozos")).doubleValue()));
        }
        assertThat(rows.get(header + 1 + summary.size())).isEqualTo(Arrays.asList("Total general", null,
                ((Long) expected.get("total")).doubleValue()));
        assertThat(rows.get(header + 2 + summary.size())).containsExactly("Ver la hoja «Listado de pozos» (35 filas).");
    }

    @Test
    void aDetailHasItsOwnSheetWithoutHiddenColumnsInPlanOrder() {
        SheetLayout detail = sheet(LayoutBuilder.build(RenderFixtures.paA()), "Listado de pozos");
        List<List<Object>> rows = rows(detail);
        assertThat(rows.getFirst()).containsExactly("Nombre", "Campo", "País", "Estado o región", "Estatus");
        assertThat(rows.size()).isEqualTo(36);
        assertThat(detail.freezeRows()).isEqualTo(1);
        assertThat(detail.filter()).isEqualTo(new SheetLayout.FilterRange(0, 35, 0, 4));
        int guadua = find(rows, "Guadua-1");
        assertThat(rows.get(guadua)).isEqualTo(Arrays.asList("Guadua-1", null, "Colombia", "Boyacá", "abandonado"));
        assertThat(rows.get(find(rows, "=Cuenca-9")).size()).isEqualTo(5);
    }

    @Test
    void theSecondReportIsLaidOutByTheSameCode() {
        WorkbookLayout layout = LayoutBuilder.build(RenderFixtures.empresas());
        List<List<Object>> rows = rows(sheet(layout, "Reporte"));
        int header = find(rows, "Operadora");
        assertThat(rows.get(header + 1)).isEqualTo(List.of("Operadora Ficticia Dos", 1.0));
        assertThat(rows.get(header + 2)).isEqualTo(List.of("Operadora Ficticia Uno", 3.0));
        assertThat(rows.get(header + 3)).isEqualTo(List.of("(sin valor)", 1.0));
        assertThat(rows.get(header + 4)).isEqualTo(List.of("Total general", 5.0));
        int filters = find(rows, "Filtros");
        assertThat(rows.get(filters).get(1)).isEqualTo(
                "Empresas activas por operadora: wellnode_sintetico.etp_company.is_active es igual a Sí");
        assertThat(rows.get(filters + 2).get(1)).isEqualTo("Detalle de empresas: Código es uno de GPA, SIC-FLU, GEA, "
                + "GPA-SER");
        assertThat(rows.get(filters + 3).get(1)).isEqualTo("Detalle de empresas: Nombre no está vacío");
        List<List<Object>> detail = rows(sheet(layout, "Detalle de empresas"));
        assertThat(detail.getFirst()).containsExactly("Nombre", "Código", "Matriz");
        assertThat(detail.get(1)).containsExactly("Grupo Petrolero Atlas", "GPA");
        assertThat(detail.get(2)).containsExactly("Servicios GPA", "GPA-SER", 10.0);
        List<List<Object>> methodology = rows(sheet(layout, "Metodología"));
        int relationship = find(methodology, "Relación");
        assertThat(methodology.get(relationship + 1)).containsExactly("c0000000-0000-4000-8000-000000000021",
                "SEMANTIC", "APPROVED", "No disponible: el plan no indica quién aprobó la relación");
    }

    @Test
    void theMethodologyStatesExecutionOriginAndRules() {
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(RenderFixtures.paA()), "Metodología"));
        assertThat(rows.getFirst()).containsExactly("Ejecución");
        assertThat(valueOf(rows, "Aislamiento")).isEqualTo("REPEATABLE_READ, solo lectura, 1 transacción");
        assertThat(valueOf(rows, "Instantánea de la lectura (pg_current_snapshot)")).isEqualTo("1234:1234:");
        int datasets = find(rows, "Conjunto de datos");
        assertThat(rows.get(datasets + 1)).isEqualTo(List.of("resumen", 15.0, "No", 10000.0));
        assertThat(rows.get(datasets + 3)).isEqualTo(List.of("detalle", 35.0, "No", 10000.0));
        assertThat(rows.get(find(rows, "Origen") + 2)).containsExactly("wellnode_sintetico.wm_well", "TABLE");
        assertThat(rows.get(find(rows, "pozos registrados")).get(1)).isEqualTo("INFERRED");
        int order = find(rows, "Reglas") + 2;
        assertThat(rows.get(order)).containsExactly("Conjunto de datos", "Columna", "Orden", "Intercalación");
        assertThat(rows.get(order + 1)).containsExactly("resumen", "País", "ASC, NULLS LAST", "C");
        assertThat(valueOf(rows, "Columnas excluidas"))
                .isEqualTo("No disponible: las columnas excluidas no se proporcionaron");
        assertThat(((String) valueOf(rows, "Modelo")).startsWith("No disponible")).isTrue();
        assertThat(((String) valueOf(rows, "Unidades")).startsWith("No disponible")).isTrue();
    }

    @Test
    void aTruncatedDatasetMakesThePartialStatusAndAWarning() {
        ReportResult r = RenderFixtures.truncatedPaA();
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(r), "Reporte"));
        assertThat(valueOf(rows, "Estado")).isEqualTo("Parcial");
        assertThat(valueOf(rows, "Aviso")).isEqualTo("«Listado de pozos» entregó 3 filas, el límite; la fuente tiene "
                + "más filas.");
        assertThat(rows.get(find(rows, "Ver la hoja «Listado de pozos» (3 filas)."))).hasSize(1);
    }

    @Test
    void theLabelsFollowTheLanguageOfTheReport() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        m(documents.spec.get("parameters")).put("labelLanguage", "en");
        documents.reseal();
        WorkbookLayout layout = LayoutBuilder.build(RenderFixtures.paA(documents, RenderFixtures.paARows(),
                RenderFixtures.complete(3)));
        assertThat(layout.sheetNames()).containsExactly("Reporte", "Listado de pozos", "Metodología");
        List<List<Object>> rows = rows(sheet(layout, "Reporte"));
        assertThat(valueOf(rows, "Title")).isEqualTo("Reporte de pozos registrados");
        assertThat(valueOf(rows, "Filters")).isEqualTo("No filters");
        assertThat(valueOf(rows, "Status")).isEqualTo("Complete");
        assertThat(rows.get(find(rows, "Grand total")).getLast()).isEqualTo(35.0);
        assertThat(rows.get(find(rows, "(no value)")).getFirst()).isEqualTo("(no value)");
        assertThat(rows(sheet(layout, "Metodología")).getFirst()).containsExactly("Execution");
    }

    @Test
    void aValueExcelCannotHoldIsWrittenAsTextWithOneWarning() {
        List<List<List<Object>>> data = RenderFixtures.paARows();
        data.set(1, List.of(List.of((1L << 53) + 1)));
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(RenderFixtures.paA(new ContractFixtures.PaA(), data,
                RenderFixtures.complete(3))), "Reporte"));
        assertThat(rows.get(find(rows, "Total general")).getLast()).isEqualTo("9007199254740993");
        assertThat(valueOf(rows, "Aviso")).isEqualTo("Para conservar su precisión, 1 valores se escribieron como "
                + "texto: «Pozos por país y campo» «Pozos» (1).");
    }

    @Test
    void textsCutByTheResultAreReported() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        ReportResult r = RenderFixtures.assemble(TestPlans.VALIDATOR.validate(documents.tenantId,
                documents.dataSourceId, documents.reportPlan, documents.spec, documents.plans), documents.spec,
                RenderFixtures.paARows(), RenderFixtures.complete(3), new AssemblyLimits(600_000, 8));
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(r), "Reporte"));
        boolean reported = rows.stream().anyMatch(row -> row.size() == 2 && "Aviso".equals(row.getFirst())
                && ((String) row.get(1)).contains("textos recortados a 8 caracteres (marca […])"));
        assertThat(reported).isTrue();
    }

    @Test
    void theCellsLaidOutNeverExceedTheReserveOfTheResult() {
        ContractFixtures.PaA en = new ContractFixtures.PaA();
        m(en.spec.get("parameters")).put("labelLanguage", "en");
        en.reseal();
        for (ReportResult r : List.of(RenderFixtures.paA(), RenderFixtures.empresas(), RenderFixtures.truncatedPaA(),
                RenderFixtures.paA(en, RenderFixtures.paARows(), RenderFixtures.complete(3)))) {
            long cells = LayoutBuilder.build(r).cellCount();
            assertThat(cells > 0 && cells <= r.presentationCells()).as(cells + " of " + r.presentationCells())
                    .isTrue();
        }
    }

    @Test
    void theLayoutIsDeterministicAndCanBeReadTwice() {
        WorkbookLayout first = LayoutBuilder.build(RenderFixtures.paA());
        WorkbookLayout second = LayoutBuilder.build(RenderFixtures.paA());
        for (int i = 0; i < first.sheets().size(); i++) {
            assertThat(rows(second.sheets().get(i))).isEqualTo(rows(first.sheets().get(i)));
            assertThat(rows(first.sheets().get(i))).isEqualTo(rows(first.sheets().get(i)));
        }
        assertThat(first.cellCount()).isEqualTo(second.cellCount());
    }

    @Test
    void everyTextStartingWithAFormulaTriggerIsMarkedLiteral() {
        long marked = 0;
        for (ReportResult r : List.of(RenderFixtures.paA(), RenderFixtures.empresas())) {
            for (SheetLayout s : LayoutBuilder.build(r).sheets()) {
                for (LayoutRow row : s.rows()) {
                    for (LayoutCell c : row.cells()) {
                        if (c.value() instanceof CellValue.Text t && !t.value().isEmpty()
                                && CellValue.FORMULA_TRIGGERS.indexOf(t.value().charAt(0)) >= 0) {
                            assertThat(t.quotePrefix()).as(t.value()).isTrue();
                            marked++;
                        }
                    }
                }
            }
        }
        assertThat(marked).isEqualTo(1L);
    }

    private static Object noValue(Object value) {
        return value == null ? "(sin valor)" : value;
    }
}
