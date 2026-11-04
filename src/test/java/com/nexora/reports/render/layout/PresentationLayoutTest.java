package com.nexora.reports.render.layout;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.render.RenderFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.render.LayoutAccess.sheet;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-19 (D-XL-1): the layout tells the writer what each cell is and what category its value has, from the generic
 * types and roles of the result only; it changes no value. The column types decide the categories of headers, data
 * and totals; a null group key is {@link ValueKind#NO_VALUE}; the header fields never size columns; a partial status
 * is a warning; each sheet says what it is; the page footer follows the report's language.
 */
class PresentationLayoutTest {

    private static List<LayoutCell> cells(SheetLayout sheet) {
        List<LayoutCell> all = new ArrayList<>();
        for (LayoutRow row : sheet.rows()) {
            all.addAll(row.cells());
        }
        return all;
    }

    private static LayoutCell cell(SheetLayout sheet, String text) {
        return cells(sheet).stream().filter(c -> c.value() instanceof CellValue.Text t && t.value().equals(text))
                .findFirst().orElseThrow(() -> new AssertionError("no cell " + text));
    }

    @Test
    void headersDataAndTotalsTakeTheCategoryOfTheirColumn() {
        WorkbookLayout layout = LayoutBuilder.build(RenderFixtures.paA());
        SheetLayout report = sheet(layout, "Reporte");
        assertThat(cell(report, "País").kind()).isEqualTo(ValueKind.TEXT);
        assertThat(cell(report, "Pozos").kind()).isEqualTo(ValueKind.INTEGER);
        assertThat(cell(report, "Pozos").style()).isEqualTo(StyleKind.TABLE_HEADER);
        assertThat(cells(report).stream().filter(c -> c.style() == StyleKind.DATA && c.value()
                instanceof CellValue.Number).allMatch(c -> c.kind() == ValueKind.INTEGER)).isTrue();
        LayoutCell total = cells(report).stream().filter(c -> c.style() == StyleKind.TOTAL_VALUE).findFirst()
                .orElseThrow();
        assertThat(total.kind()).isEqualTo(ValueKind.INTEGER);
        assertThat(cell(report, "(sin valor)").kind()).isEqualTo(ValueKind.NO_VALUE);
        assertThat(cell(report, "Argentina").kind()).isEqualTo(ValueKind.TEXT);
    }

    @Test
    void theHeaderFieldsNeverSizeColumnsAndAPartialStatusIsAWarning() {
        SheetLayout report = sheet(LayoutBuilder.build(RenderFixtures.paA()), "Reporte");
        assertThat(cell(report, "Completo").style()).isEqualTo(StyleKind.HEADER_VALUE);
        assertThat(cell(report, "Sin filtros").style()).isEqualTo(StyleKind.HEADER_VALUE);
        assertThat(StyleKind.HEADER_VALUE.sizesColumn()).isFalse();
        assertThat(cell(report, "Solicitud").style()).isEqualTo(StyleKind.LABEL);
        SheetLayout partial = sheet(LayoutBuilder.build(RenderFixtures.truncatedPaA()), "Reporte");
        assertThat(cell(partial, "Parcial").style()).isEqualTo(StyleKind.WARNING);
    }

    @Test
    void eachSheetSaysWhatItIsAndTheFooterFollowsTheLanguage() {
        WorkbookLayout layout = LayoutBuilder.build(RenderFixtures.paA());
        assertThat(layout.sheets().stream().map(SheetLayout::kind).toList()).containsExactly(
                SheetLayout.Kind.REPORT, SheetLayout.Kind.DETAIL, SheetLayout.Kind.METHODOLOGY);
        assertThat(layout.pageFooter()).isEqualTo("Página {0} de {1}");
        ContractFixtures.PaA en = new ContractFixtures.PaA();
        m(en.spec.get("parameters")).put("labelLanguage", "en");
        en.reseal();
        assertThat(LayoutBuilder.build(RenderFixtures.paA(en, RenderFixtures.paARows(), RenderFixtures.complete(3)))
                .pageFooter()).isEqualTo("Page {0} of {1}");
    }

    @Test
    void theMethodologyTablesAreTablesWithTypedColumns() {
        SheetLayout methodology = sheet(LayoutBuilder.build(RenderFixtures.paA()), "Metodología");
        LayoutCell no = cells(methodology).stream().filter(c -> c.value() instanceof CellValue.Text t
                && t.value().equals("No") && c.style() == StyleKind.DATA).findFirst().orElseThrow();
        assertThat(no.kind()).isEqualTo(ValueKind.BOOLEAN);
        assertThat(cell(methodology, "Huella de la especificación").style()).isEqualTo(StyleKind.LABEL);
    }

    @Test
    void aCellWithoutAnExplicitCategoryTakesTheOneOfItsValue() {
        assertThat(new LayoutCell(0, CellValue.text("a"), StyleKind.DATA).kind()).isEqualTo(ValueKind.TEXT);
        assertThat(new LayoutCell(0, new CellValue.Number(1, ValueFormat.INTEGER_FORMAT), StyleKind.DATA).kind())
                .isEqualTo(ValueKind.INTEGER);
        assertThat(new LayoutCell(0, new CellValue.Number(1, "#,##0.00"), StyleKind.DATA).kind())
                .isEqualTo(ValueKind.DECIMAL);
        assertThat(new LayoutCell(0, new CellValue.DateTime(java.time.LocalDateTime.of(2026, 1, 1, 0, 0),
                ValueFormat.DATE_FORMAT), StyleKind.DATA).kind()).isEqualTo(ValueKind.DATE);
        assertThat(new LayoutCell(0, new CellValue.DateTime(java.time.LocalDateTime.of(2026, 1, 1, 0, 0),
                ValueFormat.DATE_TIME_FORMAT), StyleKind.DATA).kind()).isEqualTo(ValueKind.DATE_TIME);
        for (com.nexora.reports.execution.domain.OutputValueType t
                : com.nexora.reports.execution.domain.OutputValueType.values()) {
            assertThat(ValueFormat.kindOf(t)).as(t.name()).isNotNull();
        }
    }
}
