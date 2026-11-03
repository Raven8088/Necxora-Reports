package com.nexora.reports.result;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.execution.infrastructure.ConnectionPools;
import com.nexora.reports.execution.infrastructure.PostgresReadOnlyExecutor;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.domain.AssemblyInput;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.DatasetResult;
import com.nexora.reports.result.domain.ReportItem;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.result.domain.ResultStatus;
import com.nexora.reports.result.domain.ResultWarning;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.support.BenchDatabase;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B6 end to end with a real PostgreSQL and the synthetic bench ({@link BenchDatabase}): documents validated by
 * B2, translated by B3, read in one snapshot by B4 and assembled by B6, for PA-A and for a structurally different
 * report, by the same classes. The tenancy check of A4-B5 is replaced here by an explicit, accepting, test-only one:
 * this test is about assembly, and the bench is not registered.
 */
class ResultAssemblyIntegrationTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 100, Duration.ofSeconds(2));
    private static final RunBudget BUDGET = new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(2),
            Duration.ofSeconds(20), 100, Duration.ofSeconds(2), 30_000, RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
    /** Test-only: accepts, so that assembly can be tested on the unregistered bench. */
    private static final TenantScopeVerifier ACCEPT_FOR_THIS_TEST = (credentials, catalog) -> { };

    private final PlanV2Translator translator = new PlanV2Translator();
    private final ResultAssembler assembler = new ResultAssembler();
    private final List<ConnectionPools> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPools::close);
    }

    @Test
    void paAFromTheBenchIsAssembledExactlyAsExpected() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();

        ReportResult r = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);

        Map<String, Object> expected = ContractFixtures.READER.readObject(
                ContractFixtures.bytes("nexora-testbench/reportes/esperado/pa-a.json"), "pa-a.json");
        List<List<Object>> summary = new ArrayList<>();
        for (Object row : l(expected.get("resumen"))) {
            summary.add(Arrays.asList(m(row).get("pais"), m(row).get("campo"), m(row).get("pozos")));
        }
        assertThat(r.status()).isEqualTo(ResultStatus.COMPLETED);
        assertThat(r.dataset("resumen").rows()).isEqualTo(summary);
        ReportItem.SummaryTable item = (ReportItem.SummaryTable) r.sections().getFirst().items().getFirst();
        assertThat(item.total().orElseThrow().cell("pozos").orElseThrow().value()).isEqualTo(expected.get("total"));
        DatasetResult detail = r.dataset("detalle");
        assertThat(detail.rowCount()).isEqualTo(35);
        assertThat(detail.visibleColumns()).hasSize(5);
        assertThat(r.run().snapshot().matches("\\d+:\\d+:[\\d,]*")).isTrue();
    }

    @Test
    void aStructurallyDifferentReportFromTheBenchIsAssembledByTheSameClasses() {
        ReportFixtures.Empresas documents = new ReportFixtures.Empresas(TestPlans.BENCH);

        ReportResult r = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);

        assertThat(r.status()).isEqualTo(ResultStatus.COMPLETED);
        assertThat(r.dataset("por_operadora").rows()).containsExactly(List.of("Operadora Ficticia Dos", 1L),
                List.of("Operadora Ficticia Uno", 3L), Arrays.asList(null, 1L));
        ReportItem.SummaryTable item = (ReportItem.SummaryTable) r.sections().getFirst().items().getFirst();
        assertThat(item.total().orElseThrow().cell("empresas").orElseThrow().value()).isEqualTo(5L);
        assertThat(r.dataset("detalle_empresas").rowCount() > 0).isTrue();
        assertThat(r.header().filters()).hasSize(4);
        assertThat(r.methodology().relationships()).hasSize(1);
    }

    @Test
    void aRealTruncationMakesTheReportPartial() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();
        for (Object op : documents.operations("detalle")) {
            if ("LIMIT".equals(m(op).get("op"))) {
                m(op).put("maxRows", 3L);
            }
        }
        documents.reseal();

        ReportResult r = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);

        assertThat(r.status()).isEqualTo(ResultStatus.PARTIAL);
        assertThat(r.dataset("detalle").rowCount()).isEqualTo(3);
        assertThat(r.warnings().getFirst()).isEqualTo(new ResultWarning(ResultWarning.DATASET_TRUNCATED, "detalle",
                null, 3, 3));
    }

    @Test
    void twoRunsOverTheSameDataGiveTheSameDataCells() {
        ContractFixtures.PaA documents = new ContractFixtures.PaA();

        ReportResult first = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);
        ReportResult second = run(documents.tenantId, documents.dataSourceId, documents.reportPlan, documents.spec,
                documents.plans);

        for (DatasetResult d : first.datasets()) {
            assertThat(second.dataset(d.datasetId()).rows()).isEqualTo(d.rows());
            assertThat(second.dataset(d.datasetId()).columns()).isEqualTo(d.columns());
        }
        assertThat(second.sections()).isEqualTo(first.sections());
    }

    // ------------------------------------------------------------------ helpers

    private ReportResult run(UUID tenant, UUID source, Map<String, Object> reportPlan, Map<String, Object> spec,
                             Map<UUID, Map<String, Object>> plans) {
        ValidatedReportPlan plan = TestPlans.VALIDATOR.validate(tenant, source, reportPlan, spec, plans);
        SpecPresentation presentation = SpecPresentation.from(spec, plan, ContractFixtures.SHA256);
        List<SqlQueryV2> queries = plan.datasets().stream().map(translator::translate).toList();
        ConnectionPools pool = new ConnectionPools(LIMITS);
        pools.add(pool);
        DataSourceCredentials credentials = BenchDatabase.reader(tenant, source, 4);
        Instant started = Instant.now();
        SnapshotRead read = new PostgresReadOnlyExecutor(pool, 16).executeAll(credentials,
                ExecutionRequestV2.of(plan, queries), BUDGET, ACCEPT_FOR_THIS_TEST);
        return assembler.assemble(new AssemblyInput(UUID.randomUUID(), plan, presentation, queries, read, started,
                Instant.now(), Instant.now(), "0.1.0-SNAPSHOT", Optional.empty(), Optional.empty(),
                AssemblyLimits.DEFAULTS));
    }
}
