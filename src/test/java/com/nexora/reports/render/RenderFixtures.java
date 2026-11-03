package com.nexora.reports.render;

import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.ReportFixtures;
import com.nexora.reports.result.domain.AssemblyInput;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;

/**
 * A4-B7: generic results of A4-B6 for the presentation tests, assembled by the real {@link ResultAssembler} from
 * documents validated by B2 and translated by B3, with rows in the executor's form: PA-A with the rows of
 * {@code esperado/pa-a.json}, and the structurally different «Empresas» report with synthetic rows. A public copy of
 * what {@code result.domain.ResultInputs} does, so that the closed B6 test helper is not modified.
 */
public final class RenderFixtures {

    public static final UUID RUN = UUID.fromString("b7000000-0000-4000-8000-000000000001");
    public static final Instant STARTED = Instant.parse("2026-11-01T18:00:00Z");
    public static final Instant FINISHED = Instant.parse("2026-11-01T18:00:02Z");
    public static final Instant GENERATED = Instant.parse("2026-11-01T18:00:03Z");
    public static final String SNAPSHOT = "1234:1234:";
    private static final PlanV2Translator TRANSLATOR = new PlanV2Translator();

    private RenderFixtures() {
    }

    public static ReportResult paA() {
        return paA(new ContractFixtures.PaA(), paARows(), complete(3));
    }

    public static ReportResult paA(ContractFixtures.PaA documents, List<List<List<Object>>> rows,
                                   List<Boolean> truncated) {
        ValidatedReportPlan plan = TestPlans.VALIDATOR.validate(documents.tenantId, documents.dataSourceId,
                documents.reportPlan, documents.spec, documents.plans);
        return assemble(plan, documents.spec, rows, truncated, AssemblyLimits.DEFAULTS);
    }

    public static ReportResult empresas() {
        return empresas(new ReportFixtures.Empresas(TestPlans.BENCH), empresasRows(), complete(3));
    }

    public static ReportResult empresas(ReportFixtures.Empresas documents, List<List<List<Object>>> rows,
                                        List<Boolean> truncated) {
        return assemble(documents.validate(), documents.spec, rows, truncated, AssemblyLimits.DEFAULTS);
    }

    /** B3 → (rows) → B6 for any validated report: what the executor would deliver, assembled for real. */
    public static ReportResult assemble(ValidatedReportPlan plan, Map<String, Object> spec,
                                        List<List<List<Object>>> rows, List<Boolean> truncated,
                                        AssemblyLimits limits) {
        SpecPresentation presentation = SpecPresentation.from(spec, plan, ContractFixtures.SHA256);
        List<SqlQueryV2> queries = plan.datasets().stream().map(TRANSLATOR::translate).toList();
        List<DatasetRows> read = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            read.add(new DatasetRows(queries.get(i).datasetId(), queries.get(i).outputs(), rows.get(i),
                    truncated.get(i), Duration.ofMillis(10 + i)));
        }
        return new ResultAssembler().assemble(new AssemblyInput(RUN, plan, presentation, queries,
                new SnapshotRead(SNAPSHOT, read, Duration.ofMillis(40)), STARTED, FINISHED, GENERATED,
                "0.1.0-SNAPSHOT", Optional.of("Banco sintético"), Optional.empty(), limits));
    }

    /** PA-A with its detail limited to 3 rows and truncated, as B4 reports it: a partial report. */
    public static ReportResult truncatedPaA() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        for (Object op : documents.operations("detalle")) {
            if ("LIMIT".equals(m(op).get("op"))) {
                m(op).put("maxRows", 3L);
            }
        }
        documents.reseal();
        List<List<List<Object>>> rows = paARows();
        rows.set(2, new ArrayList<>(rows.get(2).subList(0, 3)));
        return paA(documents, rows, List.of(false, false, true));
    }

    /** The rows of {@code esperado/pa-a.json}, typed as the executor reads them: resumen, total, detalle. */
    public static List<List<List<Object>>> paARows() {
        Map<String, Object> expected = paAExpected();
        List<List<Object>> summary = new ArrayList<>();
        for (Object row : l(expected.get("resumen"))) {
            summary.add(new ArrayList<>(Arrays.asList(m(row).get("pais"), m(row).get("campo"),
                    m(row).get("pozos"))));
        }
        List<List<Object>> detail = new ArrayList<>();
        for (Object row : l(expected.get("detalle"))) {
            Map<String, Object> r = m(row);
            detail.add(new ArrayList<>(Arrays.asList(r.get("nombre"), r.get("campo"), r.get("pais"), r.get("estado"),
                    r.get("estatus"), r.get("dbid"))));
        }
        List<List<Object>> total = new ArrayList<>();
        total.add(new ArrayList<>(List.of(expected.get("total"))));
        return new ArrayList<>(List.of(summary, total, detail));
    }

    public static Map<String, Object> paAExpected() {
        return ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
    }

    /** Synthetic rows of the second report: summary by operator, its total, detail (nombre, codigo, matriz, id). */
    public static List<List<List<Object>>> empresasRows() {
        return new ArrayList<>(List.of(
                new ArrayList<>(List.of(List.of("Operadora Ficticia Dos", 1L), List.of("Operadora Ficticia Uno", 3L),
                        Arrays.asList(null, 1L))),
                new ArrayList<>(List.of(List.of(5L))),
                new ArrayList<>(List.of(Arrays.asList("Grupo Petrolero Atlas", "GPA", null, 10L),
                        List.of("Servicios GPA", "GPA-SER", 10L, 11L)))));
    }

    public static List<Boolean> complete(int datasets) {
        return Collections.nCopies(datasets, false);
    }
}
