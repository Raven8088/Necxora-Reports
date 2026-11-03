package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.result.domain.AssemblyInput;
import com.nexora.reports.result.domain.AssemblyLimits;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultAssembler;
import com.nexora.reports.result.domain.SpecPresentation;
import com.nexora.reports.support.AuthenticPlans;
import com.nexora.reports.support.MemoryMeasurements;
import com.nexora.reports.support.MemoryMeasurements.Retained;
import com.nexora.reports.support.TenantDatabase;
import com.nexora.reports.support.TlsBench;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B10-c.2, C2-2 (R4-6, v3 §4 bis.5(a), D-C2-1, D-C2-3): the rows of the authentic {@code volumen} case (B10-a:
 * 35 / 1 / 10 000 of 12 000 rows), read by the real executor from the bench behind real TLS, compared with the
 * estimate production checks while reading ({@code E}). Plans are validated and translated by the real B2 and B3;
 * the authentic documents are used as they are. The tenant scope check is the controlled one of the executor tests:
 * this class measures memory, tenancy is proven elsewhere.
 * <p>
 * Asserted, tolerance 0, in each of three repetitions:
 * <ul>
 *   <li>{@code A}: every stored row (wrapper, list, array, values) of every dataset, {@code A <= E};</li>
 *   <li>{@code B}: {@code A} plus the lists holding the rows of each dataset, {@code B <= E}.</li>
 * </ul>
 * Recorded only ({@code target/b10c2-evidence/memoria-volumen.json}): the figures without discount; {@code C}, the
 * transient peak of building {@link DatasetRows} (the rows as read and their copies coexist; computed with JOL on
 * containers of the same shape); {@code D}, the whole {@link ReportResult} with the rows it shares (the memory of a
 * run after assembly, for the aggregate memory of D-C2-3); the bytes allocated by the reading thread and the peak of
 * the heap pools. A green run does not show that a whole execution stays under 64 MiB: the estimate bounds the rows
 * only. {@code D} reaches records: JOL measures them with {@code -Djol.magicFieldOffset=true} (Surefire), and every
 * size it uses is checked against the JVM's own first ({@code JolLayoutCheck}). A measurement that fails is written
 * to the evidence with its error and the failure is thrown again.
 */
class VolumeMemoryIntegrationTest {

    private static final AuthenticPlans PLANS = AuthenticPlans.load();
    private static final String CASE = "volumen";
    private static final int REPETITIONS = 3;
    /** Production defaults of {@code nexora.reports.execution} (application.yml). */
    private static final ExecutionLimits LIMITS = new ExecutionLimits(1_000, 10_000, Duration.ofSeconds(30),
            Duration.ofSeconds(2), Duration.ofSeconds(60), 500, Duration.ofSeconds(2));

    @Test
    void theRowsOfTheVolumeCaseRetainNoMoreThanTheirEstimate() {
        TlsBench.ensure();
        UUID tenant = PLANS.tenantId();
        UUID source = PLANS.dataSourceId();
        Map<String, Object> spec = PLANS.spec(CASE);
        ValidatedReportPlan plan = TestPlans.VALIDATOR.validate(tenant, source, PLANS.reportPlan(CASE), spec,
                PLANS.plans(CASE));
        PlanV2Translator translator = new PlanV2Translator();
        List<SqlQueryV2> queries = plan.datasets().stream().map(translator::translate).toList();
        ExecutionRequestV2 request = ExecutionRequestV2.of(plan, queries);
        DataSourceCredentials credentials = TenantDatabase.credentials(tenant, source, TlsBench.DATABASE,
                TlsBench.READER);
        RunBudget budget = RunBudget.of(plan.reportPlan().budget(), LIMITS, RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
        SpecPresentation presentation = SpecPresentation.from(spec, plan, ContractFixtures.SHA256);

        List<Map<String, Object>> repetitions = new ArrayList<>();
        List<long[]> checks = new ArrayList<>();
        Map<String, Object> sameAsProduction = new LinkedHashMap<>();
        try {
            try (ConnectionPools pools = new ConnectionPools(LIMITS)) {
                PostgresReadOnlyExecutor executor = new PostgresReadOnlyExecutor(pools, 16);
                for (int i = 0; i < REPETITIONS; i++) {
                    MemoryMeasurements.Probe probe = MemoryMeasurements.Probe.start();
                    SnapshotRead read = executor.executeAll(credentials, request, budget,
                            ControlledTenantScope.accepting());
                    Map<String, Object> reading = probe.stop();

                    assertThat(read.dataset("resumen").rows()).hasSize(35);
                    assertThat(read.dataset("total").rows()).hasSize(1);
                    assertThat(read.dataset("detalle").rows()).hasSize(10_000);
                    assertThat(read.truncated()).isTrue();

                    List<List<Object>> rows = new ArrayList<>();
                    List<Object> rowLists = new ArrayList<>();
                    long transientContainers = 0;
                    Map<String, Object> perDataset = new LinkedHashMap<>();
                    for (DatasetRows dataset : read.datasets()) {
                        rows.addAll(dataset.rows());
                        rowLists.add(dataset.rows());
                        long asRead = asReadContainers(dataset);
                        transientContainers += asRead;
                        perDataset.put(dataset.datasetId(), Map.of("rows", (long) dataset.rows().size(),
                                "columns", (long) dataset.columns().size(),
                                "estimateBytes", MemoryMeasurements.estimate(List.of(dataset)),
                                "rowsAsReadContainerBytes", asRead));
                    }
                    List<Object> values = MemoryMeasurements.values(rows);
                    long estimate = MemoryMeasurements.estimate(read.datasets());
                    Retained a = MemoryMeasurements.retained(rows, values);
                    Retained b = MemoryMeasurements.retained(rowLists, values);

                    ReportResult result = new ResultAssembler().assemble(new AssemblyInput(UUID.randomUUID(), plan,
                            presentation, queries, read, Instant.now(), Instant.now(), Instant.now(), "b10c2-test",
                            Optional.of("Banco sintético"), Optional.empty(), AssemblyLimits.DEFAULTS));
                    List<Object> withResult = new ArrayList<>(rowLists);
                    withResult.add(result);
                    Retained d = MemoryMeasurements.retained(withResult, values);

                    Map<String, Object> repetition = new LinkedHashMap<>();
                    repetition.put("estimateBytes", estimate);
                    repetition.put("A", a.toMap());
                    repetition.put("B", b.toMap());
                    repetition.put("A/E", MemoryMeasurements.ratio(a.attributable(), estimate));
                    repetition.put("B/E", MemoryMeasurements.ratio(b.attributable(), estimate));
                    repetition.put("C_transientBuildPeakBytes", b.attributable() + transientContainers);
                    repetition.put("D_withReportResult", d.toMap());
                    repetition.put("D/E", MemoryMeasurements.ratio(d.attributable(), estimate));
                    repetition.put("reading", reading);
                    repetition.put("datasets", perDataset);
                    repetitions.add(repetition);
                    checks.add(new long[] {estimate, a.attributable(), b.attributable()});
                }
            }

            // E is exactly what production adds up: a budget of E reads, a budget of E - 1 is refused.
            long e = checks.getFirst()[0];
            try (ConnectionPools pools = new ConnectionPools(LIMITS)) {
                PostgresReadOnlyExecutor executor = new PostgresReadOnlyExecutor(pools, 16);
                sameAsProduction.put("budgetE", outcome(() -> executor.executeAll(credentials, request,
                        withBytes(budget, e), ControlledTenantScope.accepting())));
                sameAsProduction.put("budgetEMinus1", outcome(() -> executor.executeAll(credentials, request,
                        withBytes(budget, e - 1), ControlledTenantScope.accepting())));
            }
        } catch (RuntimeException | Error failure) {
            // A measurement that fails leaves what was measured and the failure in the evidence, then fails.
            writeEvidence(repetitions, sameAsProduction, failure);
            throw failure;
        }
        writeEvidence(repetitions, sameAsProduction, null);

        assertThat(sameAsProduction.get("budgetE")).isEqualTo("READ");
        assertThat(sameAsProduction.get("budgetEMinus1")).isEqualTo("RESULT_TOO_LARGE");
        for (int i = 0; i < checks.size(); i++) {
            long[] c = checks.get(i);
            assertThat(c[1]).as("repetition %s: A %s bytes over E %s bytes", i + 1, c[1], c[0])
                    .isLessThanOrEqualTo(c[0]);
            assertThat(c[2]).as("repetition %s: B %s bytes over E %s bytes", i + 1, c[2], c[0])
                    .isLessThanOrEqualTo(c[0]);
        }
    }

    private static void writeEvidence(List<Map<String, Object>> repetitions, Map<String, Object> sameAsProduction,
                                      Throwable failure) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("test", VolumeMemoryIntegrationTest.class.getName());
        if (failure != null) {
            evidence.put("error", failure.getClass().getName());
            evidence.put("message", String.valueOf(failure.getMessage()));
        }
        evidence.put("case", CASE);
        evidence.put("manifestSha256", AuthenticPlans.sha256(AuthenticPlans.bytes("manifest.json")));
        evidence.put("estimateBudgetBytes", RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);
        evidence.put("asserted", "A <= E and B <= E in every repetition, tolerance 0");
        evidence.put("informative", List.of("figures without discount", "C", "D", "reading.allocatedBytes",
                "reading.heapPeakBytes"));
        try {
            evidence.put("jvm", MemoryMeasurements.jvm());
        } catch (RuntimeException | Error unavailable) {
            evidence.put("jvm", "unavailable: " + unavailable.getClass().getName());
        }
        evidence.put("repetitions", repetitions);
        evidence.put("productionAccumulator", sameAsProduction);
        MemoryMeasurements.writeEvidence("memoria-volumen.json", evidence);
    }

    private static RunBudget withBytes(RunBudget b, long maxEstimatedBytes) {
        return new RunBudget(b.statementTimeout(), b.lockTimeout(), b.maxDuration(), b.fetchSize(), b.acquireTimeout(),
                b.maxTotalRows(), maxEstimatedBytes);
    }

    /** {@code READ}, or the code of the refusal. */
    private static String outcome(java.util.function.Supplier<SnapshotRead> read) {
        try {
            read.get();
            return "READ";
        } catch (ExecutionException e) {
            return e.code().name();
        }
    }

    /**
     * The containers the reader builds before {@link DatasetRows} copies them, measured on lists of the same shape:
     * one {@code new ArrayList<>(width)} per row and the growing list that holds them. They coexist with the copies
     * for a moment (the values are shared, not copied).
     */
    private static long asReadContainers(DatasetRows dataset) {
        int width = dataset.columns().size();
        int count = dataset.rows().size();
        List<Object> row = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            row.add(null);
        }
        long perRow = MemoryMeasurements.retained(List.of(row), List.of()).total();
        List<Object> outer = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            outer.add(null);
        }
        return perRow * count + MemoryMeasurements.retained(List.of(outer), List.of()).total();
    }
}
