package com.nexora.reports.render.layout;

import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.result.domain.DatasetResult;
import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultColumn2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.nexora.reports.render.LayoutAccess.find;
import static com.nexora.reports.render.LayoutAccess.rows;
import static com.nexora.reports.render.LayoutAccess.sheet;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR3-INT (EX-20): a report whose specification declares the evidence of its terms shows, under the request, the
 * correspondences the model interpreted or inferred and the columns no approved definition governs; every such column
 * is marked in its header from the plan (never from its label); the methodology shows intent, entity and evidence.
 * A report of any other specification keeps exactly its grid.
 */
class InterpretationLayoutTest {

    /** PA-A with the evidence of its terms declared as Intelligence writes it. */
    private static ReportResult declared() {
        ReportResult base = RenderFixtures.paA();
        Methodology m = base.methodology();
        List<Methodology.Term> terms = new ArrayList<>();
        for (Methodology.Term t : m.terms()) {
            terms.add(new Methodology.Term(t.text(), t.resolution(), t.target(), "CATEGORY", "pozo",
                    "INFERRED".equals(t.resolution()) ? "INTERPRETED" : "NAMED"));
        }
        terms.add(new Methodology.Term("cuántos", "GOVERNED", "métrica de prueba", "MEASURE", "pozo",
                "INTERPRETED"));
        Methodology withEvidence = new Methodology(m.relations(), m.relationships(), terms, m.definitions(),
                m.orderRules(), m.datasets(), m.metadataSnapshot(), m.excludedColumns(), m.model(), m.units());
        return new ReportResult(base.run(), base.header(), base.status(), base.datasets(), base.sections(),
                withEvidence, base.warnings(), base.presentationCells());
    }

    private static List<String> inferredLabels(ReportResult result) {
        List<String> labels = new ArrayList<>();
        for (DatasetResult d : result.datasets()) {
            for (ResultColumn2 c : d.visibleColumns()) {
                if ("INFERRED".equals(c.origin()) && !labels.contains(c.label())) {
                    labels.add(c.label());
                }
            }
        }
        return labels;
    }

    @Test
    void theInterpretationIsSummarizedUnderTheRequest() {
        ReportResult result = declared();
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(result), "Reporte"));
        int interpretation = find(rows, "Interpretación");
        assertThat(interpretation).isEqualTo(find(rows, "Solicitud") + 1);
        List<String> lines = new ArrayList<>();
        for (int i = interpretation; i < find(rows, "Fuente"); i++) {
            lines.add((String) rows.get(i).get(1));
        }
        assertThat(lines).contains("«cuántos» → métrica de prueba (interpretado por el modelo)");
        result.methodology().terms().stream().filter(t -> "INFERRED".equals(t.resolution())).forEach(t ->
                assertThat(lines).contains("«" + t.text() + "» → " + t.target() + " (inferido, no gobernado)"));
        List<String> inferred = inferredLabels(result);
        assertThat(inferred).isNotEmpty();
        assertThat(lines.getLast()).isEqualTo("Columnas no gobernadas: " + String.join(", ", inferred));
        // Terms the user named literally are not listed as interpretations.
        result.methodology().terms().stream().filter(t -> "NAMED".equals(t.evidence())
                && !"INFERRED".equals(t.resolution())).forEach(t ->
                assertThat(lines).noneMatch(l -> l.startsWith("«" + t.text() + "»")));
    }

    @Test
    void everyNonGovernedColumnIsMarkedFromThePlan() {
        ReportResult result = declared();
        List<List<Object>> report = rows(sheet(LayoutBuilder.build(result), "Reporte"));
        String first = inferredLabels(result).getFirst();
        assertThat(report).anyMatch(r -> r.contains(first + " (no gobernada)"));
        assertThat(report).noneMatch(r -> r.contains(first));
    }

    @Test
    void theMethodologyShowsIntentEntityAndEvidence() {
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(declared()), "Metodología"));
        int header = find(rows, "Término");
        assertThat(rows.get(header)).containsExactly("Término", "Resolución", "Destino", "Intención", "Entidad",
                "Evidencia");
        assertThat(rows.subList(header + 1, rows.size())).anyMatch(r -> r.size() == 6 && "cuántos".equals(r.get(0))
                && "MEASURE".equals(r.get(3)) && "pozo".equals(r.get(4)) && "INTERPRETED".equals(r.get(5)));
    }

    @Test
    void aReportWithoutDeclaredEvidenceKeepsItsGrid() {
        ReportResult legacy = RenderFixtures.paA();
        List<List<Object>> report = rows(sheet(LayoutBuilder.build(legacy), "Reporte"));
        assertThat(report).noneMatch(r -> !r.isEmpty() && "Interpretación".equals(r.getFirst()));
        assertThat(report).noneMatch(r -> r.stream().anyMatch(c -> c instanceof String s
                && s.endsWith("(no gobernada)")));
        List<List<Object>> methodology = rows(sheet(LayoutBuilder.build(legacy), "Metodología"));
        assertThat(methodology.get(find(methodology, "Término"))).containsExactly("Término", "Resolución",
                "Destino");
    }

    @Test
    void aTermChosenByTheUserInAClarificationIsShownAsSuchNeverAsInterpreted() {
        ReportResult base = declared();
        Methodology m = base.methodology();
        List<Methodology.Term> terms = new ArrayList<>();
        for (Methodology.Term t : m.terms()) {
            terms.add("cuántos".equals(t.text()) ? new Methodology.Term("registros", "GOVERNED", "métrica «Pozos»",
                    "MEASURE", "pozo", "CLARIFIED") : t);
        }
        ReportResult clarified = new ReportResult(base.run(), base.header(), base.status(), base.datasets(),
                base.sections(), new Methodology(m.relations(), m.relationships(), terms, m.definitions(),
                m.orderRules(), m.datasets(), m.metadataSnapshot(), m.excludedColumns(), m.model(), m.units()),
                base.warnings(), base.presentationCells());
        List<List<Object>> rows = rows(sheet(LayoutBuilder.build(clarified), "Reporte"));
        List<String> lines = new ArrayList<>();
        for (int i = find(rows, "Interpretación"); i < find(rows, "Fuente"); i++) {
            lines.add((String) rows.get(i).get(1));
        }
        assertThat(lines).contains("«registros» → métrica «Pozos» (elegido por el usuario en una aclaración)");
        assertThat(lines).noneMatch(l -> l.startsWith("«registros»") && l.contains("interpretado"));
        List<List<Object>> methodology = rows(sheet(LayoutBuilder.build(clarified), "Metodología"));
        assertThat(methodology).anyMatch(r -> r.size() == 6 && "registros".equals(r.get(0))
                && "CLARIFIED".equals(r.get(5)));
    }
}
