package com.nexora.reports.result.domain;

import com.nexora.reports.execution.domain.OutputValueType;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.result.domain.ResultAssemblyException.Code;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;
import static com.nexora.reports.result.domain.SpecPresentationTest.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B6: the generic assembler over PA-A (the real contract documents and the rows of {@code esperado/pa-a.json})
 * and over a second, structurally different report ({@code ReportFixtures.Empresas}), with the same code.
 */
class ResultAssemblerTest {

    private final ResultAssembler assembler = new ResultAssembler();

    // --- PA-A ---

    @Test
    void paAIsCompleteWithItsSectionAndItemsInTheSpecificationOrder() {
        ReportResult r = paA();

        assertThat(r.status()).isEqualTo(ResultStatus.COMPLETED);
        assertThat(r.warnings()).isEmpty();
        assertThat(r.sections()).hasSize(1);
        assertThat(r.sections().getFirst().title()).isEqualTo("Pozos registrados");
        assertThat(r.sections().getFirst().items().stream().map(ReportItem::datasetId).toList())
                .containsExactly("resumen", "detalle");
        assertThat(r.sections().getFirst().items().getFirst()).isInstanceOf(ReportItem.SummaryTable.class);
        assertThat(r.sections().getFirst().items().get(1)).isInstanceOf(ReportItem.Table.class);
        assertThat(r.datasets().stream().map(DatasetResult::rowCount).toList()).containsExactly(15, 1, 35);
    }

    @Test
    void headersAreThePlanLabelsInThePlanOrderAndTheHiddenKeyIsKeptButNotVisible() {
        DatasetResult detail = paA().dataset("detalle");

        assertThat(detail.visibleColumns().stream().map(ResultColumn2::label).toList())
                .containsExactly("Nombre", "Campo", "País", "Estado o región", "Estatus");
        assertThat(detail.columns()).hasSize(6);
        ResultColumn2 key = detail.column("row_key");
        assertThat(key.hidden()).isTrue();
        assertThat(key.role()).isEqualTo("KEY");
        assertThat(key.valueType()).isEqualTo(OutputValueType.INTEGER);
        assertThat(key.sourceObject()).isEqualTo(new SourceObject("COLUMN", "wellnode_sintetico", "wm_well", "dbid"));
        assertThat(detail.title()).isEqualTo("Listado de pozos");
        assertThat(detail.mode()).isEqualTo("DETAIL");
    }

    @Test
    void theRowsAreTheExecutorsRowsWithoutAnyCopy() {
        ResultInputs.Report report = ResultInputs.paA();
        AssemblyInput input = report.input(ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS,
                Optional.empty());

        ReportResult r = assembler.assemble(input);

        for (int i = 0; i < 3; i++) {
            assertThat(r.datasets().get(i).rows() == input.read().datasets().get(i).rows()).isTrue();
        }
        assertThatThrownBy(() -> r.dataset("detalle").rows().getFirst().set(0, "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theTotalComesOnlyFromTheTotalDatasetEvenIfTheSummaryIsAltered() {
        List<List<List<Object>>> rows = new ArrayList<>(ResultInputs.paARows());
        rows.set(0, rows.get(0).stream().map(r -> Arrays.<Object>asList(r.get(0), r.get(1), 1L)).toList());

        ReportResult r = assemble(ResultInputs.paA(), rows, ResultInputs.complete(3), AssemblyLimits.DEFAULTS);

        ReportItem.SummaryTable summary = (ReportItem.SummaryTable) r.sections().getFirst().items().getFirst();
        ReportItem.Total total = summary.total().orElseThrow();
        assertThat(total.datasetId()).isEqualTo("total");
        assertThat(total.cell("pozos").orElseThrow().value()).isEqualTo(35L);
        assertThat(total.cells()).hasSize(1);
    }

    @Test
    void aTruncatedDatasetMakesTheReportPartialWithAWarningThatStatesRowsAndLimit() {
        ReportResult r = assemble(ResultInputs.paA(), ResultInputs.paARows(), List.of(false, false, true),
                AssemblyLimits.DEFAULTS);

        assertThat(r.status()).isEqualTo(ResultStatus.PARTIAL);
        assertThat(r.warnings()).containsExactly(new ResultWarning(ResultWarning.DATASET_TRUNCATED, "detalle", null,
                35, 10_000));
        assertThat(r.methodology().datasets().get(2).truncated()).isTrue();
    }

    @Test
    void aLongTextIsCutToTheExcelLimitWithAMarkAndOnlyItsRowIsRebuilt() {
        List<List<List<Object>>> rows = new ArrayList<>(ResultInputs.paARows());
        List<List<Object>> detail = new ArrayList<>(rows.get(2));
        List<Object> first = new ArrayList<>(detail.getFirst());
        first.set(0, "N".repeat(40_000));
        detail.set(0, first);
        rows.set(2, detail);
        ResultInputs.Report report = ResultInputs.paA();
        AssemblyInput input = report.input(rows, ResultInputs.complete(3), AssemblyLimits.DEFAULTS, Optional.empty());

        ReportResult r = assembler.assemble(input);

        String cut = (String) r.dataset("detalle").rows().getFirst().getFirst();
        assertThat(cut.length()).isEqualTo(32_767);
        assertThat(cut.endsWith(CellText.MARK)).isTrue();
        assertThat(r.warnings()).containsExactly(new ResultWarning(ResultWarning.CELL_TEXT_TRUNCATED, "detalle",
                "nombre", 1, 32_767));
        assertThat(r.dataset("detalle").rows().get(1) == input.read().datasets().get(2).rows().get(1)).isTrue();
        assertThat(r.status()).isEqualTo(ResultStatus.COMPLETED);
    }

    @Test
    void theCellBudgetCountsWhatThePresentationAddsAndRefusesBeyondIt() {
        ResultInputs.Report report = ResultInputs.paA();

        ReportResult r = assemble(report, ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS);

        // data: summary (15 + header + title + total) x 3 = 54; detail (35 + 2) x 5 + citation = 186; section 1;
        // header block 2 x 16 = 32; methodology 4 x (24 + 1 relation + 5 terms + 5 order rules + 8 x 3) = 236
        assertThat(r.presentationCells()).isEqualTo(509L);
        assertThat(assemble(report, ResultInputs.paARows(), ResultInputs.complete(3),
                new AssemblyLimits(509, 32_767)).presentationCells()).isEqualTo(509L);
        assertThat(code(() -> assemble(report, ResultInputs.paARows(), ResultInputs.complete(3),
                new AssemblyLimits(508, 32_767)))).isEqualTo(Code.BUDGET_EXCEEDED);
    }

    @Test
    void theMethodologyStatesWhatIsKnownAndMarksTheRestUnavailable() {
        ReportResult r = paA();
        Methodology m = r.methodology();

        assertThat(m.relations()).containsExactly(new SourceObject("TABLE", "wellnode_sintetico", "wm_well", null));
        assertThat(m.relationships()).isEmpty();
        assertThat(m.terms()).hasSize(5);
        assertThat(m.terms().getFirst().target()).isEqualTo("wellnode_sintetico.wm_well (una fila por pozo)");
        assertThat(m.definitions()).isEmpty();
        assertThat(m.orderRules()).contains(new Methodology.OrderRule("detalle", "pais", "ASC", "LAST", "C"),
                new Methodology.OrderRule("detalle", "row_key", "ASC", "LAST", null));
        assertThat(m.datasets().stream().map(Methodology.DatasetExecution::rowCount).toList())
                .containsExactly(15, 1, 35);
        assertThat(m.metadataSnapshot()).isInstanceOf(Fact.Known.class);
        assertThat(m.excludedColumns()).isEqualTo(Fact.unavailable(Methodology.EXCLUDED_COLUMNS_NOT_PROVIDED));
        assertThat(m.model()).isEqualTo(Fact.unavailable(Methodology.MODEL_NOT_RECORDED));
        assertThat(m.units()).isEqualTo(Fact.unavailable(Methodology.UNITS_NOT_IN_CONTRACTS));
        assertThat(r.header().tenantName()).isEqualTo(Fact.unavailable(ResultAssembler.TENANT_NAME_UNKNOWN));
        assertThat(r.header().sourceName()).isEqualTo(Fact.known("Banco sintético"));
    }

    @Test
    void dataProvidedByTheRunIsKnownAndNothingElseIsInvented() {
        ResultInputs.Report report = ResultInputs.paA();
        AssemblyInput input = report.input(ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS,
                Optional.of("3 columnas excluidas por la política (datos personales)"));
        AssemblyInput withoutSource = new AssemblyInput(input.runId(), input.plan(), input.spec(), input.queries(),
                input.read(), input.startedAt(), input.finishedAt(), input.generatedAt(), input.reportsVersion(),
                Optional.empty(), Optional.empty(), input.limits());

        assertThat(assembler.assemble(input).methodology().excludedColumns())
                .isEqualTo(Fact.known("3 columnas excluidas por la política (datos personales)"));
        assertThat(assembler.assemble(withoutSource).header().sourceName())
                .isEqualTo(Fact.unavailable(ResultAssembler.SOURCE_NAME_NOT_PROVIDED));
    }

    @Test
    void runMetadataAndHeaderComeFromTheValidatedDocumentsAndTheSnapshot() {
        ResultInputs.Report report = ResultInputs.paA();
        ReportResult r = assemble(report, ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS);

        assertThat(r.run().specFingerprint()).isEqualTo(report.plan().reportPlan().specFingerprint());
        assertThat(r.run().reportPlanFingerprint()).isEqualTo(report.plan().reportPlan().fingerprint());
        assertThat(r.run().snapshot()).isEqualTo(ResultInputs.SNAPSHOT);
        assertThat(r.run().isolation()).isEqualTo("REPEATABLE_READ");
        assertThat(r.run().transactionCount()).isEqualTo(1);
        assertThat(r.header().timeZone()).isEqualTo(ZoneId.of("America/Mexico_City"));
        assertThat(r.header().title()).isEqualTo("Reporte de pozos registrados");
        assertThat(r.header().filters()).isEmpty();
        assertThat(r.methodology().datasets().stream().map(Methodology.DatasetExecution::planFingerprint).toList())
                .isEqualTo(report.plan().reportPlan().plans().stream()
                        .map(com.nexora.reports.plan2.domain.ReportPlanV1.PlanRef::planFingerprint).toList());
    }

    @Test
    void theSameInputGivesAnEqualResult() {
        ResultInputs.Report report = ResultInputs.paA();

        assertThat(assemble(report, ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS))
                .isEqualTo(assemble(report, ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS));
    }

    @Test
    void governedOutputsCarryTheirDefinitionKeyAndRevision() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        Map<String, Object> governedSpec = ContractFixtures.read("examples/valid/pa-a.gobernado.report-spec.json");
        documents.spec.clear();
        documents.spec.putAll(governedSpec);
        Map<String, Object> resumen = documents.plan("resumen");
        resumen.clear();
        resumen.putAll(ContractFixtures.read("examples/valid/pa-a.gobernado.resumen.logical-plan.json"));
        // the governed specification has two datasets (resumen, total): the report plan follows it
        documents.plans.values().removeIf(p -> "detalle".equals(p.get("datasetId")));
        l(documents.reportPlan.get("plans")).removeIf(ref -> "detalle".equals(m(ref).get("datasetId")));
        documents.reseal();

        ReportResult r = assemble(ResultInputs.paA(documents), ResultInputs.paARows().subList(0, 2),
                ResultInputs.complete(2), AssemblyLimits.DEFAULTS);

        assertThat(r.dataset("resumen").column("pais").definition())
                .isEqualTo(new GovernedDefinition("DIMENSION", "pais", 1));
        assertThat(r.dataset("resumen").column("pozos").definition())
                .isEqualTo(new GovernedDefinition("METRIC", "pozos", 1));
        assertThat(r.dataset("resumen").column("campo").definition()).isNull();
        assertThat(r.methodology().definitions()).hasSize(2);
    }

    // --- D-A4-7 and basic-a ---

    @Test
    void aTotalMeasureWithoutASummaryMeasureOfTheSameIdIsRefused() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        Map<String, Object> total = documents.plan("total");
        for (Object op : l(total.get("operations"))) {
            if ("AGGREGATE".equals(m(op).get("op"))) {
                m(l(m(op).get("measures")).getFirst()).put("output", "total_pozos");
            }
        }
        m(l(m(total.get("output")).get("columns")).getFirst()).put("output", "total_pozos");
        m(l(m(l(documents.spec.get("datasets")).get(1)).get("select")).getFirst()).put("id", "total_pozos");
        documents.reseal();

        assertThat(code(() -> assemble(ResultInputs.paA(documents), ResultInputs.paARows(), ResultInputs.complete(3),
                AssemblyLimits.DEFAULTS))).isEqualTo(Code.REPORT_LAYOUT_INVALID);
    }

    @Test
    void aTotalDatasetWithoutExactlyOneRowIsRefused() {
        for (List<List<Object>> totalRows : List.of(List.<List<Object>>of(), List.of(List.<Object>of(35L),
                List.<Object>of(36L)))) {
            List<List<List<Object>>> rows = new ArrayList<>(ResultInputs.paARows());
            rows.set(1, totalRows);

            assertThat(code(() -> assemble(ResultInputs.paA(), rows, ResultInputs.complete(3),
                    AssemblyLimits.DEFAULTS))).isEqualTo(Code.REPORT_LAYOUT_INVALID);
        }
    }

    @Test
    void aTruncatedTotalIsRefused() {
        assertThat(code(() -> assemble(ResultInputs.paA(), ResultInputs.paARows(), List.of(false, true, false),
                AssemblyLimits.DEFAULTS))).isEqualTo(Code.REPORT_LAYOUT_INVALID);
    }

    @Test
    void itemsThatDoNotCorrespondToTheirDatasetsAreRefused() {
        ResultInputs.Report report = ResultInputs.paA();
        SpecPresentation spec = report.spec();
        List<List<SpecPresentation.Item>> invalid = List.of(
                List.of(new SpecPresentation.Item("summaryTable", "resumen", "resumen"),
                        new SpecPresentation.Item("table", "detalle", null)),
                List.of(new SpecPresentation.Item("summaryTable", "resumen", "detalle")),
                List.of(new SpecPresentation.Item("table", "resumen", null),
                        new SpecPresentation.Item("table", "detalle", null),
                        new SpecPresentation.Item("summaryTable", "total", null)),
                List.of(new SpecPresentation.Item("kpi", "total", null),
                        new SpecPresentation.Item("summaryTable", "resumen", null),
                        new SpecPresentation.Item("table", "detalle", null)),
                List.of(new SpecPresentation.Item("summaryTable", "resumen", "total")),
                List.of(new SpecPresentation.Item("summaryTable", "resumen", "total"),
                        new SpecPresentation.Item("table", "no_existe", null)));
        for (List<SpecPresentation.Item> items : invalid) {
            SpecPresentation altered = new SpecPresentation(spec.fingerprint(), spec.title(), spec.requestText(),
                    spec.requestLanguage(), spec.timeZone(), spec.labelLanguage(), spec.datasets(),
                    List.of(new SpecPresentation.Section("pozos", "Pozos", items)), 0, spec.terms());

            assertThat(code(() -> assembler.assemble(input(report, altered))))
                    .isEqualTo(Code.REPORT_LAYOUT_INVALID);
        }
    }

    @Test
    void compositionsAreRefusedInBasicA() {
        ResultInputs.Report report = ResultInputs.paA();
        SpecPresentation spec = report.spec();
        SpecPresentation withComposition = new SpecPresentation(spec.fingerprint(), spec.title(), spec.requestText(),
                spec.requestLanguage(), spec.timeZone(), spec.labelLanguage(), spec.datasets(), spec.sections(), 1,
                spec.terms());

        assertThat(code(() -> assembler.assemble(input(report, withComposition))))
                .isEqualTo(Code.REPORT_LAYOUT_INVALID);
    }

    @Test
    void inputsThatDoNotBelongToThePlanAreAProgrammingError() {
        ResultInputs.Report report = ResultInputs.paA();
        List<com.nexora.reports.translation2.domain.SqlQueryV2> swapped = new ArrayList<>(report.queries());
        java.util.Collections.swap(swapped, 0, 2);
        ResultInputs.Report other = new ResultInputs.Report(report.plan(), report.spec(), swapped);

        assertThatThrownBy(() -> assemble(other, ResultInputs.paARows(), ResultInputs.complete(3),
                AssemblyLimits.DEFAULTS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValueOfAnotherTypeThanItsColumnIsRefused() {
        List<List<List<Object>>> rows = new ArrayList<>(ResultInputs.paARows());
        rows.set(1, List.of(List.of(35)));

        assertThatThrownBy(() -> assemble(ResultInputs.paA(), rows, ResultInputs.complete(3), AssemblyLimits.DEFAULTS))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- a second, structurally different report: same code ---

    @Test
    void aStructurallyDifferentReportIsAssembledByTheSameCode() {
        ReportResult r = assemble(ResultInputs.empresas(), ResultInputs.empresasRows(), ResultInputs.complete(3),
                AssemblyLimits.DEFAULTS);

        assertThat(r.status()).isEqualTo(ResultStatus.COMPLETED);
        assertThat(r.header().title()).isEqualTo("Empresas activas por operadora");
        assertThat(r.header().timeZone()).isEqualTo(ZoneId.of("America/Bogota"));
        ReportItem.SummaryTable summary = (ReportItem.SummaryTable) r.sections().getFirst().items().getFirst();
        assertThat(summary.datasetId()).isEqualTo("por_operadora");
        assertThat(summary.total().orElseThrow().cell("empresas").orElseThrow().value()).isEqualTo(5L);
        assertThat(r.dataset("por_operadora").visibleColumns().stream().map(ResultColumn2::label).toList())
                .containsExactly("Operadora", "Empresas");
        assertThat(r.dataset("detalle_empresas").visibleColumns().stream().map(ResultColumn2::output).toList())
                .containsExactly("nombre", "codigo", "matriz");
        assertThat(r.dataset("detalle_empresas").column("row_key").hidden()).isTrue();
        assertThat(r.dataset("detalle_empresas").rows().getFirst().get(2)).isNull();
    }

    @Test
    void theSecondReportsMethodologyNamesItsTablesItsApprovedRelationshipAndItsOrder() {
        Methodology m = assemble(ResultInputs.empresas(), ResultInputs.empresasRows(), ResultInputs.complete(3),
                AssemblyLimits.DEFAULTS).methodology();

        assertThat(m.relations()).containsExactly(
                new SourceObject("TABLE", "wellnode_sintetico", "etp_company", null),
                new SourceObject("TABLE", "wellnode_sintetico", "bkw_operators", null));
        assertThat(m.relationships()).hasSize(1);
        assertThat(m.relationships().getFirst().status()).isEqualTo("APPROVED");
        assertThat(m.relationships().getFirst().approvedBy())
                .isEqualTo(Fact.unavailable(Methodology.RELATIONSHIP_APPROVER_NOT_IN_PLAN));
        assertThat(m.orderRules()).contains(
                new Methodology.OrderRule("detalle_empresas", "matriz", "DESC", "FIRST", null),
                new Methodology.OrderRule("por_operadora", "operadora", "ASC", "LAST", "C"));
        assertThat(m.terms()).hasSize(2);
    }

    @Test
    void theSecondReportsFiltersAreDescribedForTheHeader() {
        List<FilterDescription> filters = assemble(ResultInputs.empresas(), ResultInputs.empresasRows(),
                ResultInputs.complete(3), AssemblyLimits.DEFAULTS).header().filters();

        assertThat(filters).hasSize(4);
        FilterDescription in = filters.get(2);
        assertThat(in.datasetId()).isEqualTo("detalle_empresas");
        assertThat(in.columnLabel()).isEqualTo("Código");
        assertThat(in.sourceObject()).isEqualTo(new SourceObject("COLUMN", "wellnode_sintetico", "etp_company",
                "code"));
        assertThat(in.operator()).isEqualTo("IN");
        assertThat(in.values().stream().map(SpecPresentation.Literal::value).toList())
                .containsExactly("GPA", "SIC-FLU", "GEA", "GPA-SER");
        assertThat(filters.getFirst().columnLabel()).isNull();
        assertThat(filters.getFirst().sourceObject().column()).isEqualTo("is_active");
    }

    @Test
    void reportsOfDifferentShapesNeverShareResultsOrIdentity() {
        ReportResult paA = paA();
        ReportResult empresas = assemble(ResultInputs.empresas(), ResultInputs.empresasRows(),
                ResultInputs.complete(3), AssemblyLimits.DEFAULTS);

        assertThat(paA.run().tenantId()).isNotEqualTo(empresas.run().tenantId());
        assertThat(paA.run().specFingerprint()).isNotEqualTo(empresas.run().specFingerprint());
        assertThat(empresas.datasets().stream().map(DatasetResult::datasetId).toList())
                .containsExactly("por_operadora", "total_empresas", "detalle_empresas");
        assertThat(empresas.header().tenantId()).isEqualTo(empresas.run().tenantId());
    }

    // ------------------------------------------------------------------ helpers

    private ReportResult paA() {
        return assemble(ResultInputs.paA(), ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS);
    }

    private ReportResult assemble(ResultInputs.Report report, List<List<List<Object>>> rows, List<Boolean> truncated,
                                  AssemblyLimits limits) {
        return assembler.assemble(report.input(rows, truncated, limits, Optional.empty()));
    }

    private static AssemblyInput input(ResultInputs.Report report, SpecPresentation spec) {
        AssemblyInput base = report.input(ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS,
                Optional.empty());
        return new AssemblyInput(base.runId(), base.plan(), spec, base.queries(), base.read(), base.startedAt(),
                base.finishedAt(), base.generatedAt(), base.reportsVersion(), base.sourceName(),
                base.excludedColumns(), base.limits());
    }
}
