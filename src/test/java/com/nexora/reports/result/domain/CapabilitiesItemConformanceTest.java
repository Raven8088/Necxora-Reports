package com.nexora.reports.result.domain;

import com.nexora.reports.plan2.domain.ReportsProfile;
import com.nexora.reports.result.domain.ResultAssemblyException.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B10 (D-A4-8; test T-10, additional to T-1…T-8 of the audit): the item types and compositions that
 * {@code nexora-capabilities/1} announces for {@code basic-a} are exactly those the assembler accepts (A4-B6, D-A4-7).
 * Every item type of {@code nexora-report-spec/1} is tried: the announced ones are presented, the others refused
 * with {@code report_layout_invalid}.
 */
class CapabilitiesItemConformanceTest {

    private final ResultAssembler assembler = new ResultAssembler();

    @Test
    void theAnnouncedItemTypesArePresented() {
        ResultInputs.Report report = ResultInputs.paA();
        List<String> used = report.spec().sections().stream().flatMap(s -> s.items().stream())
                .map(SpecPresentation.Item::type).distinct().toList();

        ReportResult result = assembler.assemble(input(report, report.spec()));

        assertThat(used).containsExactlyInAnyOrderElementsOf(ReportsProfile.ITEM_TYPES);
        assertThat(result.sections()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kpi", "chart", "text"})
    void anItemTypeThatIsNotAnnouncedIsRefused(String type) {
        assertThat(ReportsProfile.ITEM_TYPES).doesNotContain(type);
        ResultInputs.Report report = ResultInputs.paA();
        SpecPresentation spec = report.spec();
        List<SpecPresentation.Item> items = new ArrayList<>(spec.sections().getFirst().items());
        items.add(new SpecPresentation.Item(type, "total", null));
        SpecPresentation altered = new SpecPresentation(spec.fingerprint(), spec.title(), spec.requestText(),
                spec.requestLanguage(), spec.timeZone(), spec.labelLanguage(), spec.datasets(),
                List.of(new SpecPresentation.Section("pozos", "Pozos", items)), 0, spec.terms());

        assertThat(code(report, altered)).isEqualTo(Code.REPORT_LAYOUT_INVALID);
    }

    @Test
    void noCompositionIsAnnouncedAndAnyIsRefused() {
        ResultInputs.Report report = ResultInputs.paA();
        SpecPresentation spec = report.spec();
        SpecPresentation withComposition = new SpecPresentation(spec.fingerprint(), spec.title(), spec.requestText(),
                spec.requestLanguage(), spec.timeZone(), spec.labelLanguage(), spec.datasets(), spec.sections(), 1,
                spec.terms());

        assertThat(ReportsProfile.COMPOSITIONS).isEmpty();
        assertThat(code(report, withComposition)).isEqualTo(Code.REPORT_LAYOUT_INVALID);
    }

    private Code code(ResultInputs.Report report, SpecPresentation spec) {
        Throwable thrown = catchThrowable(() -> assembler.assemble(input(report, spec)));
        assertThat(thrown).isInstanceOf(ResultAssemblyException.class);
        return ((ResultAssemblyException) thrown).code();
    }

    private static AssemblyInput input(ResultInputs.Report report, SpecPresentation spec) {
        AssemblyInput base = report.input(ResultInputs.paARows(), ResultInputs.complete(3), AssemblyLimits.DEFAULTS,
                Optional.empty());
        return new AssemblyInput(base.runId(), base.plan(), spec, base.queries(), base.read(), base.startedAt(),
                base.finishedAt(), base.generatedAt(), base.reportsVersion(), base.sourceName(),
                base.excludedColumns(), base.limits());
    }
}
