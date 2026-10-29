package com.nexora.reports.plan.infrastructure;

import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.plan.domain.LogicalPlan;
import com.nexora.reports.plan.domain.PlanNotExecutableException;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.PlanViolation;
import com.nexora.reports.plan.domain.StoredPlan;
import com.nexora.reports.support.PlanFixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static com.nexora.reports.support.PlanFixtures.element;
import static com.nexora.reports.support.PlanFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanDocumentReaderTest {

    private final PlanDocumentReader reader = new PlanDocumentReader();

    @ParameterizedTest
    @ValueSource(strings = {PlanFixtures.SINGLE_OBJECT, PlanFixtures.WEEK, PlanFixtures.INNER_JOIN,
            PlanFixtures.LEFT_JOIN, PlanFixtures.LEFT_JOIN_FILTERS, PlanFixtures.IS_NOT_NULL})
    void readsEveryPlanGeneratedBySemantic(String fixture) {
        StoredPlan stored = reader.plan(bytes(PlanFixtures.text(fixture)));
        assertThat(stored.formatVersion()).isEqualTo(LogicalPlan.FORMAT_VERSION);
        assertThat(stored.plan().tenantId()).isEqualTo(PlanFixtures.TENANT);
        assertThat(stored.plan().fingerprint()).isEqualTo(stored.fingerprint());
        assertThat(stored.plan().aggregations()).isNotEmpty();
    }

    @Test
    void readsTheSingleObjectPlanFieldByField() {
        LogicalPlan plan = reader.plan(bytes(PlanFixtures.text(PlanFixtures.SINGLE_OBJECT))).plan();
        assertThat(plan.base().naturalKey()).isEqualTo("\"plant\".\"measurements\"");
        assertThat(plan.outputGrain().get(0).granularity()).isEqualTo(LogicalPlan.TimeGranularity.MONTH);
        assertThat(plan.outputGrain().get(0).timeZone()).isEqualTo("UTC");
        assertThat(plan.outputGrain().get(1).granularity()).isNull();
        assertThat(plan.preAggregationFilters().get(1).values()).containsExactly("2025-12-31T23:00Z");
        assertThat(plan.postAggregationFilters().get(0).objectId()).isNull();
        assertThat(plan.aggregations().get(1).columnObjectId()).isNull();
        assertThat(plan.operations()).extracting(LogicalPlan.PlanOperation::kind).containsExactly(
                LogicalPlan.OperationKind.SCAN, LogicalPlan.OperationKind.FILTER_ROWS,
                LogicalPlan.OperationKind.GROUP_AND_AGGREGATE, LogicalPlan.OperationKind.FILTER_GROUPS);
    }

    @Test
    void anUnknownOperationIsUnreadableNotIgnored() {
        ObjectNode envelope = PlanFixtures.mutate(PlanFixtures.SINGLE_OBJECT,
                e -> element(e, "operations", 1).put("kind", "WINDOW_FUNCTION"));
        assertThatThrownBy(() -> PlanFixtures.read(envelope))
                .isInstanceOfSatisfying(PlanNotExecutableException.class, ex -> assertThat(ex.violations())
                        .containsExactly(new PlanViolation(PlanViolation.Code.UNREADABLE_DOCUMENT,
                                "$.plan.operations[1].kind")));
    }

    @Test
    void unknownPropertiesAnywhereAreRejected() {
        ObjectNode topLevel = PlanFixtures.mutate(PlanFixtures.SINGLE_OBJECT, e -> e.put("sql", "SELECT 1"));
        ObjectNode nested = PlanFixtures.mutate(PlanFixtures.SINGLE_OBJECT,
                e -> element(e, "preAggregationFilters", 0).put("expression", "1=1"));
        for (ObjectNode envelope : new ObjectNode[]{topLevel, nested}) {
            assertThatThrownBy(() -> PlanFixtures.read(envelope))
                    .isInstanceOfSatisfying(PlanNotExecutableException.class, ex -> assertThat(ex.violations())
                            .extracting(PlanViolation::code).containsExactly(PlanViolation.Code.UNREADABLE_DOCUMENT));
        }
    }

    @Test
    void anotherFormatVersionIsUnsupportedEvenIfTheBodyLooksFamiliar() {
        ObjectNode envelope = PlanFixtures.mutate(PlanFixtures.SINGLE_OBJECT, e -> {
            e.put("formatVersion", "nexora-logical-plan/2");
            plan(e).put("formatVersion", "nexora-logical-plan/2");
        });
        assertThatThrownBy(() -> PlanFixtures.read(envelope))
                .isInstanceOfSatisfying(PlanNotExecutableException.class, ex -> assertThat(ex.violations())
                        .extracting(PlanViolation::code)
                        .containsExactly(PlanViolation.Code.UNSUPPORTED_FORMAT_VERSION));
    }

    @Test
    void scalarCoercionsAndDuplicateKeysAreRejected() {
        ObjectNode textOrder = PlanFixtures.mutate(PlanFixtures.SINGLE_OBJECT,
                e -> element(e, "aggregations", 0).put("order", "1"));
        assertThatThrownBy(() -> PlanFixtures.read(textOrder)).isInstanceOf(PlanNotExecutableException.class);

        String duplicated = PlanFixtures.text(PlanFixtures.SINGLE_OBJECT)
                .replaceFirst("\"safety\" : \"SINGLE_OBJECT\"",
                        "\"safety\" : \"SINGLE_OBJECT\", \"safety\" : \"JOINS_TO_PRIMARY_KEY_ONLY\"");
        assertThatThrownBy(() -> reader.plan(bytes(duplicated))).isInstanceOf(PlanNotExecutableException.class);

        assertThatThrownBy(() -> reader.plan(bytes("not json"))).isInstanceOf(PlanNotExecutableException.class);
        assertThatThrownBy(() -> reader.plan(bytes("[]"))).isInstanceOf(PlanNotExecutableException.class);
    }

    @Test
    void readsValidityDocumentsAndRefusesUnknownShapes() {
        PlanValidity current = reader.validity(bytes(PlanFixtures.text("validity-single-object-current.json")));
        assertThat(current.status()).isEqualTo(PlanValidity.Status.CURRENT);
        PlanValidity changed = reader.validity(
                bytes(PlanFixtures.text("validity-single-object-after-compatible-change.json")));
        assertThat(changed.issues()).singleElement().satisfies(i -> assertThat(i.blocking()).isFalse());

        assertThatThrownBy(() -> reader.validity(bytes("{\"planId\":\"" + java.util.UUID.randomUUID()
                + "\",\"status\":\"PROBABLY_CURRENT\",\"issues\":[]}")))
                .isInstanceOfSatisfying(ExecutionException.class, e -> assertThat(e.code())
                        .isEqualTo(ExecutionException.ErrorCode.PLAN_VALIDITY_UNCONFIRMED));
    }

    @Test
    void readsOnlyIdEngineAndStatusOfTheDataSource() {
        var source = reader.dataSource(bytes(PlanFixtures.text("data-source.json")));
        assertThat(source.id()).isEqualTo(PlanFixtures.DATA_SOURCE);
        assertThat(source.engine()).isEqualTo("postgresql");
        assertThat(source.status()).isEqualTo("ACTIVE");
        assertThat(reader.problemCode(bytes(PlanFixtures.text("error-plan-not-found.json")))).isEqualTo("not_found");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
