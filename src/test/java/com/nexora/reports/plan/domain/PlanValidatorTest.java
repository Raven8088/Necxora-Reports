package com.nexora.reports.plan.domain;

import com.nexora.reports.plan.domain.PlanViolation.Code;
import com.nexora.reports.support.PlanFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

import static com.nexora.reports.support.PlanFixtures.array;
import static com.nexora.reports.support.PlanFixtures.column;
import static com.nexora.reports.support.PlanFixtures.definition;
import static com.nexora.reports.support.PlanFixtures.element;
import static com.nexora.reports.support.PlanFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @ParameterizedTest
    @ValueSource(strings = {PlanFixtures.SINGLE_OBJECT, PlanFixtures.WEEK, PlanFixtures.INNER_JOIN,
            PlanFixtures.LEFT_JOIN, PlanFixtures.LEFT_JOIN_FILTERS, PlanFixtures.IS_NOT_NULL})
    void everyPlanGeneratedBySemanticIsExecutable(String fixture) {
        ValidatedPlan plan = validator.validate(PlanFixtures.read(fixture));
        assertThat(plan.tenantId()).isEqualTo(PlanFixtures.TENANT);
        assertThat(plan.relations().get(0).index()).isZero();
        assertThat(plan.measures()).isNotEmpty();
    }

    @Test
    void theCompositeJoinKeepsBothExactConditionsAndTheClaimedPrimaryKey() {
        ValidatedPlan plan = validator.validate(PlanFixtures.read(PlanFixtures.INNER_JOIN));
        assertThat(plan.relations()).extracting(r -> r.name().naturalKey())
                .containsExactly("\"finance\".\"transactions\"", "\"docs\".\"documents\"");
        ValidatedPlan.Join join = plan.joins().get(0);
        assertThat(join.type()).isEqualTo(LogicalPlan.JoinType.INNER);
        assertThat(join.conditions()).extracting(c -> c.left().name().column() + "=" + c.right().name().column())
                .containsExactly("company_code=company_code", "document_number=number");
        assertThat(join.targetKeyColumns()).containsExactly("company_code", "number");
        assertThat(plan.measures().get(0).column().relationIndex()).isZero();
        assertThat(plan.dimensions().get(0).column().relationIndex()).isEqualTo(1);
    }

    @Test
    void parsesFilterValuesByTheirDeclaredType() {
        ValidatedPlan plan = validator.validate(PlanFixtures.read(PlanFixtures.SINGLE_OBJECT));
        assertThat(plan.rowFilters().get(0).values()).containsExactly(new FilterValue.Text("ok"),
                new FilterValue.Text("revisar"));
        assertThat(plan.rowFilters().get(1).values().get(0)).isInstanceOf(FilterValue.Instant.class);
        assertThat(plan.groupFilters().get(0).value()).isEqualByComparingTo("100");
        assertThat(plan.dimensions().get(0).timeZone().getId()).isEqualTo("UTC");
    }

    // --- joins ---

    @Test
    void aJoinThatMayMultiplyRowsIsRejected() {
        assertThat(violations(PlanFixtures.LEFT_JOIN,
                e -> element(e, "joins", 0).put("multiplicationRisk", "POSSIBLE")))
                .contains(Code.MULTIPLICATION_RISK);
    }

    @Test
    void aJoinToASideWithoutPrimaryKeyEvidenceIsRejected() {
        assertThat(violations(PlanFixtures.LEFT_JOIN,
                e -> element(e, "joins", 0).put("joinedSideUniqueness", "UNKNOWN")))
                .contains(Code.UNIQUENESS_NOT_PROVEN);
        // The equipment.dbid case: declared MANY_TO_ONE, but the target column is not the primary key.
        assertThat(violations(PlanFixtures.LEFT_JOIN_FILTERS, e -> {
            ObjectNode relationship = element(e, "relationships", 0);
            relationship.put("targetUniqueness", "UNKNOWN");
            relationship.put("sourceToTargetRisk", "POSSIBLE");
            relationship.put("cardinalityAssurance", "DECLARED_ONLY");
        })).contains(Code.UNIQUENESS_NOT_PROVEN, Code.MULTIPLICATION_RISK);
    }

    @Test
    void aJoinTypeTheRelationshipDoesNotAuthorizeIsRejected() {
        assertThat(violations(PlanFixtures.INNER_JOIN, e -> {
            ObjectNode join = element(e, "joins", 0);
            join.put("joinType", "LEFT");
            join.put("rowPreservation", "PRESERVED");
            join.put("optional", true);
        })).containsExactly(Code.JOIN_NOT_AUTHORIZED);
    }

    @Test
    void reverseNavigationMustMatchTheRelationshipAndIsInnerOnly() {
        assertThat(violations(PlanFixtures.LEFT_JOIN, e -> element(e, "joins", 0).put("navigation", "REVERSE")))
                .contains(Code.JOIN_CONDITION_MISMATCH, Code.JOIN_NOT_AUTHORIZED);
    }

    @Test
    void joinConditionsMustBeExactlyTheRelationshipPairs() {
        assertThat(violations(PlanFixtures.INNER_JOIN, e -> ((ArrayNode) element(e, "joins", 0)
                .get("conditions")).remove(1))).contains(Code.JOIN_CONDITION_MISMATCH);
        // A condition whose column belongs to another relation.
        assertThat(violations(PlanFixtures.INNER_JOIN, e -> {
            String amount = column(e, "\"finance\".\"transactions\".\"amount\"").get("objectId").asString();
            ((ObjectNode) element(e, "joins", 0).get("conditions").get(0)).put("toColumnId", amount);
        })).contains(Code.UNKNOWN_REFERENCE);
    }

    @Test
    void theSameRelationCannotBeJoinedTwice() {
        assertThat(violations(PlanFixtures.LEFT_JOIN, e -> {
            ObjectNode copy = element(e, "joins", 0).deepCopy();
            copy.put("order", 2);
            array(e, "joins").add(copy);
        })).contains(Code.DUPLICATE_REFERENCE);
    }

    @Test
    void aRowFilterOnTheOptionalSideOfALeftJoinIsRejected() {
        assertThat(violations(PlanFixtures.LEFT_JOIN, e -> {
            ObjectNode name = definition(e, "nombre_equipo");
            ObjectNode equipment = PlanFixtures.find(e, "physicalObjects", "naturalKey", "\"plant\".\"equipment\"");
            ObjectNode filter = array(e, "preAggregationFilters").addObject();
            filter.put("order", 1);
            filter.put("definitionId", name.get("id").asString());
            filter.put("definitionKind", "DIMENSION");
            filter.put("operator", "EQ");
            filter.put("valueType", "TEXT");
            filter.put("objectId", equipment.get("objectId").asString());
            filter.put("columnObjectId", name.get("columnObjectId").asString());
            filter.putArray("values").add("Bomba");
            ArrayNode operations = array(e, "operations");
            operations.removeAll();
            addOperation(operations, 1, "SCAN");
            addOperation(operations, 2, "JOIN", 1);
            addOperation(operations, 3, "FILTER_ROWS", 1);
            addOperation(operations, 4, "GROUP_AND_AGGREGATE");
        })).containsExactly(Code.UNSAFE_FILTER);
    }

    // --- grain, operations, safety ---

    @Test
    void metricsMustAggregateTheBaseObjectOnly() {
        assertThat(violations(PlanFixtures.LEFT_JOIN, e -> {
            String equipmentId = column(e, "\"plant\".\"equipment\".\"id\"").get("objectId").asString();
            element(e, "aggregations", 0).put("columnObjectId", equipmentId);
            definition(e, "num_sensores").put("columnObjectId", equipmentId);
        })).contains(Code.INCONSISTENT_GRAIN);
    }

    @Test
    void operationsMustBeExactlyTheOnesThePlanImplies() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> {
            ArrayNode operations = array(e, "operations");
            ObjectNode last = (ObjectNode) operations.remove(3);
            operations.insert(1, last);
        })).containsExactly(Code.INCONSISTENT_OPERATIONS);
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> element(e, "operations", 1).putArray("references")
                .add(1))).containsExactly(Code.INCONSISTENT_OPERATIONS);
    }

    @Test
    void safetyMustMatchTheJoins() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT,
                e -> plan(e).put("safety", "JOINS_TO_PRIMARY_KEY_ONLY"))).containsExactly(Code.INCONSISTENT_SAFETY);
    }

    @Test
    void theDocumentFingerprintMustEqualTheEnvelopeFingerprintComparedAsOpaqueText() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> e.put("fingerprint", "other")))
                .containsExactly(Code.INCONSISTENT_FINGERPRINT);
    }

    @Test
    void mandatoryPartsMustBePresent() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> plan(e).remove("joins")))
                .containsExactly(Code.MISSING_FIELD);
        assertThat(violations(PlanFixtures.WEEK, e -> element(e, "outputGrain", 0).remove("granularity")))
                .containsExactly(Code.MISSING_FIELD);
    }

    // --- references and types ---

    @Test
    void referencesOutsideThePlanCopiesAreRejected() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> element(e, "outputGrain", 1)
                .put("columnObjectId", java.util.UUID.randomUUID().toString())))
                .contains(Code.UNKNOWN_REFERENCE);
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> element(e, "aggregations", 0)
                .put("metricId", java.util.UUID.randomUUID().toString())))
                .contains(Code.UNKNOWN_REFERENCE);
    }

    @Test
    void malformedNaturalKeysAreRejected() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> column(e, "\"plant\".\"measurements\".\"note\"")
                .put("naturalKey", "\"plant\".\"measurements\".note; DROP TABLE plant.measurements")))
                .contains(Code.INVALID_NATURAL_KEY);
    }

    @Test
    void typesWithoutExactSqlSemanticsInR1AreRejected() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> column(e, "\"plant\".\"measurements\".\"value\"")
                .put("dataType", "money"))).contains(Code.UNSUPPORTED_TYPE);
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> column(e, "\"plant\".\"measurements\".\"note\"")
                .put("dataType", "jsonb"))).contains(Code.INCONSISTENT_DEFINITION);
    }

    @Test
    void operatorsMustSuitTheTypeAndValuesMustParse() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> {
            ObjectNode filter = element(e, "preAggregationFilters", 0);
            filter.put("operator", "GT");
            filter.putArray("values").add("ok");
        })).containsExactly(Code.UNSUPPORTED_OPERATOR);
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> element(e, "preAggregationFilters", 1)
                .putArray("values").add("yesterday"))).containsExactly(Code.INVALID_FILTER_VALUE);
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> element(e, "postAggregationFilters", 0)
                .put("operator", "IN"))).containsExactly(Code.UNSUPPORTED_OPERATOR);
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> {
            ArrayNode values = element(e, "preAggregationFilters", 0).putArray("values");
            for (int i = 0; i <= PlanValidator.MAX_IN_VALUES; i++) {
                values.add("v" + i);
            }
        })).containsExactly(Code.COMPLEXITY_LIMIT_EXCEEDED);
        assertThat(violations(PlanFixtures.IS_NOT_NULL, e -> element(e, "preAggregationFilters", 0)
                .putArray("values").add("x"))).containsExactly(Code.INVALID_FILTER_VALUE);
    }

    @Test
    void timeZonesMustBeIanaZonesAndOnlyOnTimestampWithTimeZone() {
        assertThat(violations(PlanFixtures.WEEK, e -> {
            element(e, "outputGrain", 0).put("timeZone", "Mars/Olympus_Mons");
            definition(e, "fecha").put("timeZone", "Mars/Olympus_Mons");
        })).containsExactly(Code.INVALID_TIME_ZONE);
        assertThat(violations(PlanFixtures.WEEK, e -> {
            element(e, "outputGrain", 0).put("timeZone", "+02:00");
            definition(e, "fecha").put("timeZone", "+02:00");
        })).containsExactly(Code.INVALID_TIME_ZONE);
        assertThat(violations(PlanFixtures.WEEK, e -> element(e, "outputGrain", 0).put("timeZone",
                "Europe/Madrid"))).containsExactly(Code.INCONSISTENT_DEFINITION);
    }

    @Test
    void outputNamesMustBeUnique() {
        assertThat(violations(PlanFixtures.SINGLE_OBJECT, e -> {
            element(e, "outputGrain", 1).put("key", "suma_valor");
            definition(e, "nota").put("key", "suma_valor");
        })).contains(Code.DUPLICATE_REFERENCE);
    }

    // --- helpers ---

    private List<Code> violations(String fixture, Consumer<ObjectNode> change) {
        ObjectNode envelope = PlanFixtures.mutate(fixture, change);
        var stored = PlanFixtures.read(envelope);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> validator.validate(stored));
        assertThat(thrown).as("plan must be rejected").isInstanceOf(PlanNotExecutableException.class);
        return ((PlanNotExecutableException) thrown).violations().stream().map(PlanViolation::code).distinct()
                .toList();
    }

    private static void addOperation(ArrayNode operations, int order, String kind, int... references) {
        ObjectNode operation = operations.addObject();
        operation.put("order", order);
        operation.put("kind", kind);
        ArrayNode refs = operation.putArray("references");
        for (int reference : references) {
            refs.add(reference);
        }
    }

    @Test
    void anEmptyEnvelopeIsRejected() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(PlanNotExecutableException.class);
    }
}
