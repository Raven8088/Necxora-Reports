package com.nexora.reports.result.domain;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A4-B6 (D-B6-1): the presentation of the specification, bound to the validated plan by its fingerprint. */
class SpecPresentationTest {

    @Test
    void readsThePresentationOfTheValidatedSpecification() {
        SpecPresentation spec = ResultInputs.paA().spec();

        assertThat(spec.title()).isEqualTo("Reporte de pozos registrados");
        assertThat(spec.requestLanguage()).isEqualTo("es");
        assertThat(spec.timeZone()).isEqualTo("America/Mexico_City");
        assertThat(spec.datasets().stream().map(SpecPresentation.Dataset::id).toList())
                .containsExactly("resumen", "total", "detalle");
        assertThat(spec.dataset("detalle").title()).isEqualTo("Listado de pozos");
        assertThat(spec.sections().getFirst().items()).containsExactly(
                new SpecPresentation.Item("summaryTable", "resumen", "total"),
                new SpecPresentation.Item("table", "detalle", null));
        assertThat(spec.terms()).hasSize(5);
    }

    /**
     * PR3-INT: a specification that declares the evidence of its terms passes the validation of Reports (its copy of
     * the contract) and its evidence reaches the presentation; without it, the evidence is absent (null).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theEvidenceOfTheTermsIsReadWhenTheSpecificationDeclaresIt() {
        assertThat(ResultInputs.paA().spec().terms()).allMatch(t -> t.intent() == null && t.evidence() == null);

        ContractFixtures.PaA paA = new ContractFixtures.PaA();
        Map<String, Object> term = (Map<String, Object>) ((java.util.List<Object>) ((Map<String, Object>)
                paA.spec.get("provenance")).get("terms")).getFirst();
        term.put("intent", "CATEGORY");
        term.put("entity", "7a3f0000-0000-4000-8000-0000000000aa");
        term.put("ref", "7a3f0000-0000-4000-8000-000000000011");
        term.put("evidence", "INTERPRETED");
        paA.reseal();

        SpecPresentation.Term read = ResultInputs.paA(paA).spec().terms().getFirst();
        assertThat(read.intent()).isEqualTo("CATEGORY");
        assertThat(read.entity()).hasToString("7a3f0000-0000-4000-8000-0000000000aa");
        assertThat(read.ref()).hasToString("7a3f0000-0000-4000-8000-000000000011");
        assertThat(read.evidence()).isEqualTo("INTERPRETED");
    }

    @Test
    void aSpecificationOtherThanTheValidatedOneIsRefused() {
        ContractFixtures.PaA paA = new ContractFixtures.PaA();
        ValidatedReportPlan plan = ResultInputs.paA(paA).plan();
        Map<String, Object> other = ContractFixtures.copy(paA.spec);
        other.put("title", "Otro título");

        assertThat(code(() -> SpecPresentation.from(other, plan, ContractFixtures.SHA256)))
                .isEqualTo(ResultAssemblyException.Code.SPEC_MISMATCH);
        assertThat(code(() -> SpecPresentation.from(ContractFixtures.read("examples/valid/pa-b.report-spec.json"),
                plan, ContractFixtures.SHA256))).isEqualTo(ResultAssemblyException.Code.SPEC_MISMATCH);
    }

    @Test
    void aViewBuiltForAnotherPlanCannotBeAssembled() {
        ResultInputs.Report paA = ResultInputs.paA();
        ResultInputs.Report empresas = ResultInputs.empresas();
        AssemblyInput input = new AssemblyInput(ResultInputs.RUN, paA.plan(), empresas.spec(), paA.queries(),
                paA.input(ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS,
                        java.util.Optional.empty()).read(), ResultInputs.STARTED, ResultInputs.FINISHED,
                ResultInputs.GENERATED, "x", java.util.Optional.empty(), java.util.Optional.empty(),
                AssemblyLimits.DEFAULTS);

        assertThat(code(() -> new ResultAssembler().assemble(input)))
                .isEqualTo(ResultAssemblyException.Code.SPEC_MISMATCH);
    }

    @Test
    void selectItemsKeepTheirColumnAndGovernedDefinition() {
        SpecPresentation.Dataset detail = ResultInputs.empresas().spec().dataset("detalle_empresas");

        assertThat(detail.select().stream().map(SpecPresentation.Select::label).toList())
                .containsExactly("Nombre", "Código", "Matriz");
        assertThat(detail.filters()).hasSize(2);
        assertThat(detail.filters().getFirst().values()).hasSize(4);
    }

    static ResultAssemblyException.Code code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ResultAssemblyException.class);
        return ((ResultAssemblyException) thrown).code();
    }
}
