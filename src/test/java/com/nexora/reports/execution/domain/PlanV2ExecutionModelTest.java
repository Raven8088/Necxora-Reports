package com.nexora.reports.execution.domain;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.plan2.domain.ReportPlanV1;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B4: the pure model of a {@code /2} execution (budget, request invariants, typed rows, size estimate). */
class PlanV2ExecutionModelTest {

    private static final ExecutionLimits SERVER = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 500, Duration.ofSeconds(2));

    private final PlanV2Translator translator = new PlanV2Translator();

    @Test
    void theBudgetIsTheMinimumOfThePlanAndTheServer() {
        RunBudget generous = RunBudget.of(new ReportPlanV1.Budget(3, 10_000, 30_000, 30_000, 60_000), SERVER, 1000);
        RunBudget strict = RunBudget.of(new ReportPlanV1.Budget(3, 10_000, 500, 5_000, 8_000), SERVER, 1000);

        assertThat(generous.statementTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(generous.maxDuration()).isEqualTo(Duration.ofSeconds(20));
        assertThat(generous.maxTotalRows()).isEqualTo(30_000);
        assertThat(strict.statementTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(strict.maxDuration()).isEqualTo(Duration.ofSeconds(8));
        assertThat(strict.maxTotalRows()).isEqualTo(500);
        assertThat(strict.lockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(strict.fetchSize()).isEqualTo(500);
    }

    @Test
    void anInvalidBudgetIsRefused() {
        assertThatThrownBy(() -> RunBudget.of(new ReportPlanV1.Budget(3, 10, 30, 0, 60_000), SERVER, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunBudget.of(new ReportPlanV1.Budget(3, 10, 30, 1000, 1000), SERVER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRequestTakesTenantAndSourceFromTheValidatedPlan() {
        ValidatedReportPlan paA = TestPlans.paA();

        ExecutionRequestV2 request = ExecutionRequestV2.of(paA, queries(paA));

        assertThat(request.tenantId()).isEqualTo(paA.reportPlan().tenantId());
        assertThat(request.dataSourceId()).isEqualTo(paA.reportPlan().dataSourceId());
        assertThat(request.queries().stream().map(SqlQueryV2::datasetId).toList())
                .containsExactly("resumen", "total", "detalle");
    }

    @Test
    void queriesInAnotherOrderOrWithAnotherLimitAreRefused() {
        ValidatedReportPlan paA = TestPlans.paA();
        List<SqlQueryV2> reordered = new ArrayList<>(queries(paA));
        java.util.Collections.swap(reordered, 0, 2);
        List<SqlQueryV2> otherLimit = new ArrayList<>(queries(paA));
        otherLimit.set(0, translator.translate(paA.datasets().getFirst().plan(), 5));

        assertThatThrownBy(() -> ExecutionRequestV2.of(paA, reordered)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionRequestV2.of(paA, otherLimit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionRequestV2.of(paA, queries(paA).subList(0, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aQueryWhoseLastParameterIsNotTheRowLimitIsRefused() {
        SqlQueryV2 q = queries(TestPlans.paA()).getFirst();
        List<SqlParameter> parameters = new ArrayList<>(q.parameters());
        parameters.set(parameters.size() - 1, new SqlParameter(SqlType.BIGINT, (long) q.maxRows() + 2));
        SqlQueryV2 tampered = new SqlQueryV2(q.datasetId(), q.sql(), parameters, q.outputs(), q.maxRows(), q.schema());

        assertThatThrownBy(() -> new ExecutionRequestV2(UUID.randomUUID(), UUID.randomUUID(), List.of(tampered)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionRequestV2(UUID.randomUUID(), UUID.randomUUID(), List.of(q, q)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialsOfAnotherTenantOrSourceAreRefused() {
        ValidatedReportPlan paA = TestPlans.paA();
        ExecutionRequestV2 request = ExecutionRequestV2.of(paA, queries(paA));
        UUID tenant = paA.reportPlan().tenantId();
        UUID source = paA.reportPlan().dataSourceId();

        request.requireSameSource(credentials(tenant, source));
        assertThatThrownBy(() -> request.requireSameSource(credentials(UUID.randomUUID(), source)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request.requireSameSource(credentials(tenant, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheValueTypesOfTheContractAreRead() {
        assertThat(OutputValueType.of("TIMESTAMP_TZ")).isEqualTo(OutputValueType.TIMESTAMP_TZ);
        assertThatThrownBy(() -> OutputValueType.of("MONEY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutputValueType.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSizeEstimateGrowsWithTheValuesAndRefusesUnknownTypes() {
        long small = ResultSizeEstimate.ofRow(Arrays.asList("a", 1L, null));
        long large = ResultSizeEstimate.ofRow(Arrays.asList("a".repeat(1000), 1L, null));

        assertThat(large - small).isEqualTo(2L * 999);
        assertThat(ResultSizeEstimate.of(new BigDecimal("12345678901234567890.5")) > 56).isTrue();
        assertThat(ResultSizeEstimate.of(LocalDate.of(2026, 1, 1))).isEqualTo(24L);
        assertThatThrownBy(() -> ResultSizeEstimate.of(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void datasetRowsAreImmutableKeepNullsAndHideValues() {
        SqlQueryV2 q = queries(TestPlans.paA()).get(1);
        List<Object> row = new ArrayList<>();
        row.add(null);
        DatasetRows rows = new DatasetRows("total", q.outputs(), List.of(row), false, Duration.ZERO);
        row.set(0, 99L);

        assertThat(rows.rows().getFirst().getFirst()).isNull();
        assertThatThrownBy(() -> rows.rows().getFirst().set(0, 1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new DatasetRows("total", q.outputs(), List.of(List.of(1L, 2L)), false,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows.toString()).doesNotContain("99");
    }

    @Test
    void theSnapshotReadFindsDatasetsAndReportsTruncation() {
        SqlQueryV2 q = queries(TestPlans.paA()).get(1);
        SnapshotRead read = new SnapshotRead("100:100:", List.of(
                new DatasetRows("total", q.outputs(), List.of(List.of(35L)), false, Duration.ZERO),
                new DatasetRows("otro", q.outputs(), List.of(), true, Duration.ZERO)), Duration.ZERO);

        assertThat(read.dataset("total").rows()).isEqualTo(List.of(List.of(35L)));
        assertThat(read.truncated()).isTrue();
        assertThatThrownBy(() -> read.dataset("x")).isInstanceOf(IllegalArgumentException.class);
    }

    private List<SqlQueryV2> queries(ValidatedReportPlan plan) {
        return plan.datasets().stream().map(translator::translate).toList();
    }

    private static DataSourceCredentials credentials(UUID tenant, UUID source) {
        return new DataSourceCredentials(tenant, source, "localhost", 5432, "db", "user", "password", "disable", 1);
    }
}
