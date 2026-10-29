package com.nexora.reports.plan.domain;

import java.util.List;
import java.util.UUID;

/**
 * Reports' own, read-only model of the Semantic contract {@value #FORMAT_VERSION}. Field names and enum values are
 * exactly those of the JSON document persisted by Nexora Semantic (S4.2); Reports never builds or rewrites a plan.
 * <p>
 * Every list may be {@code null} only when the document omitted it; {@link PlanValidator} rejects such documents.
 * Semantic serializes with {@code non_null} inclusion, so optional scalar fields (for example
 * {@code granularity} of a categorical grouping key) are simply absent.
 */
public record LogicalPlan(
        String formatVersion,
        UUID tenantId,
        UUID dataSourceId,
        UUID metadataSnapshotId,
        String resolutionFingerprint,
        RequestSnapshot request,
        BaseObject base,
        InputGrain inputGrain,
        List<GroupingKey> outputGrain,
        List<PlanJoin> joins,
        List<PlanFilter> preAggregationFilters,
        List<PlanAggregation> aggregations,
        List<PlanFilter> postAggregationFilters,
        List<PlanOperation> operations,
        AggregationSafety safety,
        List<DefinitionSnapshot> definitions,
        List<RelationshipSnapshot> relationships,
        List<ObjectSnapshot> physicalObjects,
        String fingerprint) {

    public static final String FORMAT_VERSION = "nexora-logical-plan/1";

    // --- enums of the contract (closed sets: an unknown value makes the document unreadable) ---

    public enum DefinitionKind { CONCEPT, METRIC, DIMENSION }

    public enum Aggregation { COUNT, SUM, AVG }

    public enum DimensionType { CATEGORICAL, TEMPORAL }

    public enum TimeGranularity { DAY, WEEK, MONTH, QUARTER, YEAR }

    /** Category assigned by Semantic to a column type (see {@link TypeCategory}). */
    public enum ValueType { NUMERIC, TEXT, BOOLEAN, DATE, TIMESTAMP, TIMESTAMP_TZ, TIME, UUID, OTHER }

    public enum FilterOperator { EQ, NE, GT, GTE, LT, LTE, IN, IS_NULL, IS_NOT_NULL }

    public enum JoinType { INNER, LEFT }

    public enum Navigation { FORWARD, REVERSE }

    public enum RowPreservation { PRESERVED, PRESERVED_BY_FOREIGN_KEY, UNMATCHED_ROWS_EXCLUDED }

    public enum UniquenessEvidence { PRIMARY_KEY, UNKNOWN }

    public enum RowMultiplicationRisk { NONE_BY_PRIMARY_KEY, POSSIBLE }

    public enum OperationKind { SCAN, JOIN, FILTER_ROWS, GROUP_AND_AGGREGATE, FILTER_GROUPS }

    public enum AggregationSafety { SINGLE_OBJECT, JOINS_TO_PRIMARY_KEY_ONLY }

    public enum PhysicalObjectType { SCHEMA, TABLE, VIEW, MATERIALIZED_VIEW, COLUMN, FOREIGN_KEY }

    public enum RelationshipOrigin { DECLARED_FK, SEMANTIC }

    public enum DeclaredCardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }

    public enum ForeignKeyEvidence { PRESENT, LOST, NOT_DECLARED }

    public enum CardinalityAssurance { BACKED_BY_PRIMARY_KEY, DECLARED_ONLY, NO_UNIQUENESS_CLAIM }

    // --- parts ---

    public record RequestSnapshot(List<UUID> metrics, List<UUID> dimensions, List<FilterSnapshot> filters,
                                  List<UUID> preferredRelationships, List<GranularityChoice> timeGranularities) {
    }

    public record FilterSnapshot(UUID definitionId, FilterOperator operator, List<String> values) {
    }

    public record GranularityChoice(UUID dimensionId, TimeGranularity granularity) {
    }

    public record BaseObject(UUID objectId, String naturalKey) {
    }

    public record InputGrain(UUID objectId, List<String> primaryKeyColumns, UniquenessEvidence rowIdentity) {
    }

    public record GroupingKey(UUID dimensionId, String key, UUID objectId, UUID columnObjectId, DimensionType type,
                              ValueType valueType, TimeGranularity granularity, String timeZone) {
    }

    public record JoinCondition(UUID fromColumnId, UUID toColumnId) {
    }

    public record PlanJoin(Integer order, UUID relationshipId, Long relationshipVersion, UUID fromObjectId,
                           UUID toObjectId, Navigation navigation, JoinType joinType, List<JoinCondition> conditions,
                           UniquenessEvidence joinedSideUniqueness, RowMultiplicationRisk multiplicationRisk,
                           RowPreservation rowPreservation, Boolean optional) {
    }

    public record PlanFilter(Integer order, UUID definitionId, DefinitionKind definitionKind,
                             FilterOperator operator, ValueType valueType, UUID objectId, UUID columnObjectId,
                             List<String> values) {
    }

    public record PlanAggregation(Integer order, UUID metricId, String key, Aggregation aggregation,
                                  UUID columnObjectId) {
    }

    public record PlanOperation(Integer order, OperationKind kind, List<Integer> references) {
    }

    public record DefinitionSnapshot(UUID id, DefinitionKind kind, String key, String name, Long version,
                                     UUID conceptId, UUID relationObjectId, Aggregation aggregation,
                                     UUID columnObjectId, DimensionType dimensionType,
                                     List<TimeGranularity> granularities, String timeZone) {
    }

    public record PairSnapshot(UUID sourceColumnId, String sourceNaturalKey, UUID targetColumnId,
                               String targetNaturalKey) {
    }

    public record RelationshipSnapshot(UUID id, Long version, String name, RelationshipOrigin origin,
                                       UUID sourceObjectId, UUID targetObjectId, List<PairSnapshot> pairs,
                                       DeclaredCardinality declaredCardinality, List<JoinType> allowedJoinTypes,
                                       UUID foreignKeyId, UniquenessEvidence sourceUniqueness,
                                       UniquenessEvidence targetUniqueness, ForeignKeyEvidence foreignKey,
                                       CardinalityAssurance cardinalityAssurance,
                                       RowMultiplicationRisk sourceToTargetRisk,
                                       RowMultiplicationRisk targetToSourceRisk) {
    }

    public record ObjectSnapshot(UUID objectId, PhysicalObjectType type, String naturalKey, String dataType,
                                 Boolean nullable, String fingerprint, UUID lastSeenSnapshotId) {
    }
}
