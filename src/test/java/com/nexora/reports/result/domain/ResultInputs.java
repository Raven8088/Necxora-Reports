package com.nexora.reports.result.domain;

import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.ReportFixtures;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;

/**
 * Test inputs of the assembler: validated documents (B2), translated queries (B3) and rows in the executor's form
 * (B4). PA-A's rows are exactly {@code esperado/pa-a.json}; the second report's rows are synthetic.
 */
final class ResultInputs {

    static final PlanV2Translator TRANSLATOR = new PlanV2Translator();
    static final UUID RUN = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    static final Instant STARTED = Instant.parse("2026-11-01T18:00:00Z");
    static final Instant FINISHED = Instant.parse("2026-11-01T18:00:02Z");
    static final Instant GENERATED = Instant.parse("2026-11-01T18:00:03Z");
    static final String SNAPSHOT = "1234:1234:";

    private ResultInputs() {
    }

    /** One report's documents, validated, with its specification view and queries. */
    record Report(ValidatedReportPlan plan, SpecPresentation spec, List<SqlQueryV2> queries) {
        AssemblyInput input(List<List<List<Object>>> rows, List<Boolean> truncated, AssemblyLimits limits,
                            Optional<String> excluded) {
            List<DatasetRows> read = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                read.add(new DatasetRows(queries.get(i).datasetId(), queries.get(i).outputs(), rows.get(i),
                        truncated.get(i), Duration.ofMillis(10 + i)));
            }
            return new AssemblyInput(RUN, plan, spec, queries, new SnapshotRead(SNAPSHOT, read, Duration.ofMillis(40)),
                    STARTED, FINISHED, GENERATED, "0.1.0-SNAPSHOT", Optional.of("Banco sintético"), excluded, limits);
        }
    }

    static Report paA() {
        return paA(new ContractFixtures.PaA());
    }

    static Report paA(ContractFixtures.PaA documents) {
        ValidatedReportPlan plan = TestPlans.VALIDATOR.validate(documents.tenantId, documents.dataSourceId,
                documents.reportPlan, documents.spec, documents.plans);
        return report(plan, documents.spec);
    }

    static Report empresas() {
        ReportFixtures.Empresas documents = new ReportFixtures.Empresas("wellnode_sintetico");
        return report(documents.validate(), documents.spec);
    }

    static Report report(ValidatedReportPlan plan, Map<String, Object> spec) {
        return new Report(plan, SpecPresentation.from(spec, plan, ContractFixtures.SHA256),
                plan.datasets().stream().map(TRANSLATOR::translate).toList());
    }

    /** The rows of {@code esperado/pa-a.json}, typed as the executor reads them: resumen, total, detalle. */
    static List<List<List<Object>>> paARows() {
        Map<String, Object> expected = ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
        List<List<Object>> summary = new ArrayList<>();
        for (Object row : l(expected.get("resumen"))) {
            summary.add(Arrays.asList(m(row).get("pais"), m(row).get("campo"), m(row).get("pozos")));
        }
        List<List<Object>> detail = new ArrayList<>();
        for (Object row : l(expected.get("detalle"))) {
            Map<String, Object> r = m(row);
            detail.add(Arrays.asList(r.get("nombre"), r.get("campo"), r.get("pais"), r.get("estado"),
                    r.get("estatus"), r.get("dbid")));
        }
        return List.of(summary, List.of(List.of(expected.get("total"))), detail);
    }

    /** Synthetic rows of the second report: summary by operator, its total, detail (nombre, codigo, matriz, id). */
    static List<List<List<Object>>> empresasRows() {
        return List.of(
                List.of(List.of("Operadora Ficticia Dos", 1L), List.of("Operadora Ficticia Uno", 3L),
                        Arrays.asList(null, 1L)),
                List.of(List.of(5L)),
                List.of(Arrays.asList("Grupo Petrolero Atlas", "GPA", null, 10L),
                        List.of("Servicios GPA", "GPA-SER", 10L, 11L)));
    }

    static List<Boolean> complete(int datasets) {
        return java.util.Collections.nCopies(datasets, false);
    }
}
