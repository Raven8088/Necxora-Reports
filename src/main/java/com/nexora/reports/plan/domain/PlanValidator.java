package com.nexora.reports.plan.domain;

import com.nexora.reports.plan.domain.LogicalPlan.Aggregation;
import com.nexora.reports.plan.domain.LogicalPlan.AggregationSafety;
import com.nexora.reports.plan.domain.LogicalPlan.DefinitionKind;
import com.nexora.reports.plan.domain.LogicalPlan.DefinitionSnapshot;
import com.nexora.reports.plan.domain.LogicalPlan.DimensionType;
import com.nexora.reports.plan.domain.LogicalPlan.FilterOperator;
import com.nexora.reports.plan.domain.LogicalPlan.ForeignKeyEvidence;
import com.nexora.reports.plan.domain.LogicalPlan.GroupingKey;
import com.nexora.reports.plan.domain.LogicalPlan.JoinCondition;
import com.nexora.reports.plan.domain.LogicalPlan.JoinType;
import com.nexora.reports.plan.domain.LogicalPlan.Navigation;
import com.nexora.reports.plan.domain.LogicalPlan.ObjectSnapshot;
import com.nexora.reports.plan.domain.LogicalPlan.OperationKind;
import com.nexora.reports.plan.domain.LogicalPlan.PairSnapshot;
import com.nexora.reports.plan.domain.LogicalPlan.PlanAggregation;
import com.nexora.reports.plan.domain.LogicalPlan.PlanFilter;
import com.nexora.reports.plan.domain.LogicalPlan.PlanJoin;
import com.nexora.reports.plan.domain.LogicalPlan.PlanOperation;
import com.nexora.reports.plan.domain.LogicalPlan.RelationshipSnapshot;
import com.nexora.reports.plan.domain.LogicalPlan.RowMultiplicationRisk;
import com.nexora.reports.plan.domain.LogicalPlan.RowPreservation;
import com.nexora.reports.plan.domain.LogicalPlan.UniquenessEvidence;
import com.nexora.reports.plan.domain.LogicalPlan.ValueType;
import com.nexora.reports.plan.domain.PlanViolation.Code;
import com.nexora.reports.plan.domain.ValidatedPlan.Column;
import com.nexora.reports.plan.domain.ValidatedPlan.Dimension;
import com.nexora.reports.plan.domain.ValidatedPlan.Equality;
import com.nexora.reports.plan.domain.ValidatedPlan.GroupFilter;
import com.nexora.reports.plan.domain.ValidatedPlan.Join;
import com.nexora.reports.plan.domain.ValidatedPlan.Measure;
import com.nexora.reports.plan.domain.ValidatedPlan.Relation;
import com.nexora.reports.plan.domain.ValidatedPlan.RowFilter;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict validation of a {@code nexora-logical-plan/1} document before anything is executed (pure domain logic,
 * deterministic, no I/O).
 * <p>
 * Reports re-checks every safety property Semantic asserts instead of trusting it, and refuses whatever R1 cannot
 * execute exactly. It never modifies the plan to make it executable: any inconsistency is a rejection with
 * {@link PlanNotExecutableException}. The main rules are:
 * <ul>
 *   <li>format {@code nexora-logical-plan/1} only, every mandatory part present, document fingerprint equal to the
 *       envelope fingerprint (compared as opaque text);</li>
 *   <li>every object, column, definition and relationship referenced is part of the plan's own copies, with
 *       well-formed natural keys;</li>
 *   <li>joins form a tree rooted at the base object, each one to a side unique by primary key
 *       ({@code NONE_BY_PRIMARY_KEY}), authorized by its relationship snapshot in the navigation direction, with
 *       exactly the relationship's column pairs, no INNER after an optional (LEFT) side and no relation twice;</li>
 *   <li>metrics aggregate columns of the base object only; filters on rows never touch an optional side;</li>
 *   <li>types, operators, granularities and time zones are among those R1 supports, and every filter value parses
 *       for its type;</li>
 *   <li>the operation list is exactly the one the plan implies (no unknown or reordered operation).</li>
 * </ul>
 */
public final class PlanValidator {

    /** Upper bounds aligned with the limits of Semantic S4.1/S4.2. */
    public static final int MAX_JOINS = 8;
    public static final int MAX_METRICS = 20;
    public static final int MAX_DIMENSIONS = 20;
    public static final int MAX_FILTERS = 50;
    public static final int MAX_IN_VALUES = 100;

    private static final Set<ValueType> ORDERED = EnumSet.of(ValueType.NUMERIC, ValueType.DATE,
            ValueType.TIMESTAMP, ValueType.TIMESTAMP_TZ, ValueType.TIME);
    private static final Set<ValueType> TEMPORAL = EnumSet.of(ValueType.DATE, ValueType.TIMESTAMP,
            ValueType.TIMESTAMP_TZ);
    private static final Set<FilterOperator> GROUP_OPERATORS = EnumSet.of(FilterOperator.EQ, FilterOperator.NE,
            FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE);
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public ValidatedPlan validate(StoredPlan stored) {
        return new Run(stored).validate();
    }

    /** One validation, collecting violations; stops early only when later checks would be meaningless. */
    private static final class Run {

        private final StoredPlan stored;
        private final LogicalPlan plan;
        private final List<PlanViolation> violations = new ArrayList<>();
        private final Map<UUID, ObjectSnapshot> relationObjects = new HashMap<>();
        private final Map<UUID, ObjectSnapshot> columnObjects = new HashMap<>();
        private final Map<UUID, NaturalKey.RelationName> relationNames = new HashMap<>();
        private final Map<UUID, NaturalKey.ColumnName> columnNames = new HashMap<>();
        private final Map<UUID, DefinitionSnapshot> definitions = new HashMap<>();
        private final Map<UUID, RelationshipSnapshot> relationships = new HashMap<>();
        /** Relations reachable so far (object id → relation with its alias index). */
        private final Map<UUID, Relation> aliases = new LinkedHashMap<>();
        private final Set<UUID> optionalObjects = new HashSet<>();

        Run(StoredPlan stored) {
            this.stored = stored;
            this.plan = stored == null ? null : stored.plan();
        }

        ValidatedPlan validate() {
            if (stored == null || plan == null) {
                throw PlanNotExecutableException.of(Code.MISSING_FIELD, "plan");
            }
            if (!LogicalPlan.FORMAT_VERSION.equals(stored.formatVersion())
                    || !LogicalPlan.FORMAT_VERSION.equals(plan.formatVersion())) {
                throw PlanNotExecutableException.of(Code.UNSUPPORTED_FORMAT_VERSION, "plan.formatVersion");
            }
            requireStructure();
            failIfAny();

            if (!Objects.equals(stored.fingerprint(), plan.fingerprint())) {
                add(Code.INCONSISTENT_FINGERPRINT, "plan.fingerprint");
            }
            if (!Objects.equals(stored.dataSourceId(), plan.dataSourceId())) {
                add(Code.UNKNOWN_REFERENCE, "plan.dataSourceId");
            }
            limits();
            physicalObjects();
            definitionsAndRelationships();
            failIfAny();

            Relation base = base();
            failIfAny();
            List<Join> joins = joins(base);
            safety();
            List<Measure> measures = measures(base);
            List<Dimension> dimensions = dimensions(measures);
            List<RowFilter> rowFilters = rowFilters();
            List<GroupFilter> groupFilters = groupFilters();
            operations();
            failIfAny();

            return new ValidatedPlan(stored.id(), plan.tenantId(), plan.dataSourceId(), plan.fingerprint(),
                    new ArrayList<>(aliases.values()), joins, dimensions, measures, rowFilters, groupFilters);
        }

        // --- structure ---

        private void requireStructure() {
            required(plan.tenantId(), "plan.tenantId");
            required(plan.dataSourceId(), "plan.dataSourceId");
            required(plan.base(), "plan.base");
            required(plan.inputGrain(), "plan.inputGrain");
            required(plan.outputGrain(), "plan.outputGrain");
            required(plan.joins(), "plan.joins");
            required(plan.preAggregationFilters(), "plan.preAggregationFilters");
            required(plan.aggregations(), "plan.aggregations");
            required(plan.postAggregationFilters(), "plan.postAggregationFilters");
            required(plan.operations(), "plan.operations");
            required(plan.safety(), "plan.safety");
            required(plan.definitions(), "plan.definitions");
            required(plan.relationships(), "plan.relationships");
            required(plan.physicalObjects(), "plan.physicalObjects");
            required(plan.fingerprint(), "plan.fingerprint");
            required(stored.fingerprint(), "fingerprint");
            required(stored.id(), "id");
            required(stored.dataSourceId(), "dataSourceId");
            if (plan.aggregations() != null && plan.aggregations().isEmpty()) {
                add(Code.MISSING_FIELD, "plan.aggregations");
            }
            noNullElements(plan.outputGrain(), "plan.outputGrain");
            noNullElements(plan.joins(), "plan.joins");
            noNullElements(plan.preAggregationFilters(), "plan.preAggregationFilters");
            noNullElements(plan.aggregations(), "plan.aggregations");
            noNullElements(plan.postAggregationFilters(), "plan.postAggregationFilters");
            noNullElements(plan.operations(), "plan.operations");
            noNullElements(plan.definitions(), "plan.definitions");
            noNullElements(plan.relationships(), "plan.relationships");
            noNullElements(plan.physicalObjects(), "plan.physicalObjects");
        }

        private void limits() {
            if (plan.joins().size() > MAX_JOINS) {
                add(Code.COMPLEXITY_LIMIT_EXCEEDED, "plan.joins");
            }
            if (plan.aggregations().size() > MAX_METRICS) {
                add(Code.COMPLEXITY_LIMIT_EXCEEDED, "plan.aggregations");
            }
            if (plan.outputGrain().size() > MAX_DIMENSIONS) {
                add(Code.COMPLEXITY_LIMIT_EXCEEDED, "plan.outputGrain");
            }
            if (plan.preAggregationFilters().size() + plan.postAggregationFilters().size() > MAX_FILTERS) {
                add(Code.COMPLEXITY_LIMIT_EXCEEDED, "plan.preAggregationFilters");
            }
        }

        private void physicalObjects() {
            Set<String> naturalKeys = new HashSet<>();
            for (int i = 0; i < plan.physicalObjects().size(); i++) {
                ObjectSnapshot o = plan.physicalObjects().get(i);
                String path = "plan.physicalObjects[" + i + "]";
                if (o.objectId() == null || o.type() == null || o.naturalKey() == null) {
                    add(Code.MISSING_FIELD, path);
                    continue;
                }
                if (relationObjects.containsKey(o.objectId()) || columnObjects.containsKey(o.objectId())
                        || !naturalKeys.add(o.type() + "|" + o.naturalKey())) {
                    add(Code.DUPLICATE_REFERENCE, path);
                    continue;
                }
                switch (o.type()) {
                    case TABLE, VIEW, MATERIALIZED_VIEW -> NaturalKey.relation(o.naturalKey()).ifPresentOrElse(n -> {
                        relationObjects.put(o.objectId(), o);
                        relationNames.put(o.objectId(), n);
                    }, () -> add(Code.INVALID_NATURAL_KEY, path + ".naturalKey"));
                    case COLUMN -> {
                        if (o.dataType() == null || o.dataType().isBlank()) {
                            add(Code.MISSING_FIELD, path + ".dataType");
                        }
                        NaturalKey.column(o.naturalKey()).ifPresentOrElse(n -> {
                            columnObjects.put(o.objectId(), o);
                            columnNames.put(o.objectId(), n);
                        }, () -> add(Code.INVALID_NATURAL_KEY, path + ".naturalKey"));
                    }
                    // Schemas or foreign keys are never referenced by an executable element; they are ignored.
                    default -> {
                    }
                }
            }
        }

        private void definitionsAndRelationships() {
            for (int i = 0; i < plan.definitions().size(); i++) {
                DefinitionSnapshot d = plan.definitions().get(i);
                String path = "plan.definitions[" + i + "]";
                if (d.id() == null || d.kind() == null) {
                    add(Code.MISSING_FIELD, path);
                } else if (definitions.putIfAbsent(d.id(), d) != null) {
                    add(Code.DUPLICATE_REFERENCE, path);
                }
            }
            for (int i = 0; i < plan.relationships().size(); i++) {
                RelationshipSnapshot r = plan.relationships().get(i);
                String path = "plan.relationships[" + i + "]";
                if (r.id() == null || r.version() == null || r.sourceObjectId() == null || r.targetObjectId() == null
                        || r.pairs() == null || r.pairs().isEmpty() || r.allowedJoinTypes() == null
                        || r.foreignKey() == null || r.sourceUniqueness() == null || r.targetUniqueness() == null
                        || r.sourceToTargetRisk() == null || r.targetToSourceRisk() == null
                        || r.pairs().stream().anyMatch(Objects::isNull)) {
                    add(Code.MISSING_FIELD, path);
                } else if (relationships.putIfAbsent(r.id(), r) != null) {
                    add(Code.DUPLICATE_REFERENCE, path);
                }
            }
        }

        // --- base and joins ---

        private Relation base() {
            var base = plan.base();
            var grain = plan.inputGrain();
            if (base.objectId() == null || !relationObjects.containsKey(base.objectId())) {
                add(Code.UNKNOWN_REFERENCE, "plan.base.objectId");
                return null;
            }
            if (!relationObjects.get(base.objectId()).naturalKey().equals(base.naturalKey())) {
                add(Code.INVALID_NATURAL_KEY, "plan.base.naturalKey");
            }
            if (!base.objectId().equals(grain.objectId())) {
                add(Code.INCONSISTENT_GRAIN, "plan.inputGrain.objectId");
            }
            List<String> pk = grain.primaryKeyColumns();
            if (pk == null || grain.rowIdentity() == null
                    || pk.stream().anyMatch(c -> c == null || c.isEmpty())
                    || (grain.rowIdentity() == UniquenessEvidence.PRIMARY_KEY) == pk.isEmpty()) {
                add(Code.INCONSISTENT_GRAIN, "plan.inputGrain");
            }
            Relation relation = new Relation(0, base.objectId(), relationNames.get(base.objectId()));
            aliases.put(base.objectId(), relation);
            return relation;
        }

        private List<Join> joins(Relation base) {
            List<Join> result = new ArrayList<>();
            for (int i = 0; i < plan.joins().size(); i++) {
                PlanJoin j = plan.joins().get(i);
                String path = "plan.joins[" + i + "]";
                if (j.order() == null || j.relationshipId() == null || j.relationshipVersion() == null
                        || j.fromObjectId() == null || j.toObjectId() == null || j.navigation() == null
                        || j.joinType() == null || j.conditions() == null || j.conditions().isEmpty()
                        || j.conditions().stream().anyMatch(c -> c == null || c.fromColumnId() == null
                        || c.toColumnId() == null)
                        || j.joinedSideUniqueness() == null || j.multiplicationRisk() == null
                        || j.rowPreservation() == null || j.optional() == null) {
                    add(Code.MISSING_FIELD, path);
                    return result;
                }
                if (j.order() != i + 1) {
                    add(Code.INVALID_ORDER, path + ".order");
                }
                if (j.joinedSideUniqueness() != UniquenessEvidence.PRIMARY_KEY) {
                    add(Code.UNIQUENESS_NOT_PROVEN, path + ".joinedSideUniqueness");
                }
                if (j.multiplicationRisk() != RowMultiplicationRisk.NONE_BY_PRIMARY_KEY) {
                    add(Code.MULTIPLICATION_RISK, path + ".multiplicationRisk");
                }
                Relation from = aliases.get(j.fromObjectId());
                if (from == null) {
                    add(Code.UNKNOWN_REFERENCE, path + ".fromObjectId");
                    return result;
                }
                if (!relationObjects.containsKey(j.toObjectId())) {
                    add(Code.UNKNOWN_REFERENCE, path + ".toObjectId");
                    return result;
                }
                if (aliases.containsKey(j.toObjectId())) {
                    // The same relation twice (roles) is outside R1 and of Semantic S4.2.
                    add(Code.DUPLICATE_REFERENCE, path + ".toObjectId");
                    return result;
                }
                RelationshipSnapshot r = relationships.get(j.relationshipId());
                if (r == null) {
                    add(Code.UNKNOWN_REFERENCE, path + ".relationshipId");
                    return result;
                }
                if (!r.version().equals(j.relationshipVersion())) {
                    add(Code.JOIN_CONDITION_MISMATCH, path + ".relationshipVersion");
                }
                if (r.foreignKey() == ForeignKeyEvidence.LOST) {
                    add(Code.JOIN_NOT_AUTHORIZED, path + ".relationshipId");
                }
                boolean forward = j.navigation() == Navigation.FORWARD;
                UUID expectedFrom = forward ? r.sourceObjectId() : r.targetObjectId();
                UUID expectedTo = forward ? r.targetObjectId() : r.sourceObjectId();
                if (!expectedFrom.equals(j.fromObjectId()) || !expectedTo.equals(j.toObjectId())) {
                    add(Code.JOIN_CONDITION_MISMATCH, path + ".navigation");
                }
                // D36: a relationship authorizes join types from source to target; the reverse is INNER only.
                if (!r.allowedJoinTypes().contains(j.joinType())
                        || (!forward && j.joinType() != JoinType.INNER)) {
                    add(Code.JOIN_NOT_AUTHORIZED, path + ".joinType");
                }
                UniquenessEvidence joinedSide = forward ? r.targetUniqueness() : r.sourceUniqueness();
                RowMultiplicationRisk risk = forward ? r.sourceToTargetRisk() : r.targetToSourceRisk();
                if (joinedSide != UniquenessEvidence.PRIMARY_KEY) {
                    add(Code.UNIQUENESS_NOT_PROVEN, path + ".relationshipId");
                }
                if (risk != RowMultiplicationRisk.NONE_BY_PRIMARY_KEY) {
                    add(Code.MULTIPLICATION_RISK, path + ".relationshipId");
                }
                boolean fromOptional = optionalObjects.contains(j.fromObjectId());
                if (fromOptional && j.joinType() == JoinType.INNER) {
                    add(Code.JOIN_NOT_AUTHORIZED, path + ".joinType");
                }
                boolean optional = fromOptional || j.joinType() == JoinType.LEFT;
                if (j.optional() != optional) {
                    add(Code.JOIN_CONDITION_MISMATCH, path + ".optional");
                }
                boolean preservationOk = switch (j.rowPreservation()) {
                    case PRESERVED -> j.joinType() == JoinType.LEFT;
                    case UNMATCHED_ROWS_EXCLUDED -> j.joinType() == JoinType.INNER;
                    case PRESERVED_BY_FOREIGN_KEY -> j.joinType() == JoinType.INNER && forward
                            && r.foreignKey() == ForeignKeyEvidence.PRESENT;
                };
                if (!preservationOk) {
                    add(Code.JOIN_CONDITION_MISMATCH, path + ".rowPreservation");
                }

                Relation target = new Relation(i + 1, j.toObjectId(), relationNames.get(j.toObjectId()));
                List<Equality> equalities = conditions(j, r, forward, from, target, path);
                if (equalities == null) {
                    return result;
                }
                aliases.put(j.toObjectId(), target);
                if (optional) {
                    optionalObjects.add(j.toObjectId());
                }
                result.add(new Join(i + 1, j.joinType(), target, equalities,
                        equalities.stream().map(e -> e.right().name().column()).toList()));
            }
            return result;
        }

        /** The join conditions must be exactly the relationship's pairs, oriented in the navigation direction. */
        private List<Equality> conditions(PlanJoin j, RelationshipSnapshot r, boolean forward, Relation from,
                                          Relation target, String path) {
            Set<List<UUID>> expected = new HashSet<>();
            for (PairSnapshot pair : r.pairs()) {
                if (pair.sourceColumnId() == null || pair.targetColumnId() == null) {
                    add(Code.MISSING_FIELD, path + ".relationshipId");
                    return null;
                }
                checkPairKey(pair.sourceColumnId(), pair.sourceNaturalKey(), path);
                checkPairKey(pair.targetColumnId(), pair.targetNaturalKey(), path);
                expected.add(forward ? List.of(pair.sourceColumnId(), pair.targetColumnId())
                        : List.of(pair.targetColumnId(), pair.sourceColumnId()));
            }
            Set<List<UUID>> actual = new HashSet<>();
            List<Equality> equalities = new ArrayList<>();
            for (int c = 0; c < j.conditions().size(); c++) {
                JoinCondition condition = j.conditions().get(c);
                String cpath = path + ".conditions[" + c + "]";
                if (!actual.add(List.of(condition.fromColumnId(), condition.toColumnId()))) {
                    add(Code.DUPLICATE_REFERENCE, cpath);
                    return null;
                }
                Column left = column(condition.fromColumnId(), from, cpath + ".fromColumnId");
                Column right = column(condition.toColumnId(), target, cpath + ".toColumnId");
                if (left == null || right == null) {
                    return null;
                }
                if (left.category() != right.category() || !TypeCategory.executable(left.declaredType())
                        || !TypeCategory.executable(right.declaredType())) {
                    add(Code.UNSUPPORTED_TYPE, cpath);
                }
                equalities.add(new Equality(left, right));
            }
            if (!expected.equals(actual) || expected.size() != j.conditions().size()) {
                add(Code.JOIN_CONDITION_MISMATCH, path + ".conditions");
                return null;
            }
            return equalities;
        }

        private void checkPairKey(UUID columnId, String naturalKey, String path) {
            ObjectSnapshot column = columnObjects.get(columnId);
            if (column == null) {
                add(Code.UNKNOWN_REFERENCE, path + ".relationshipId");
            } else if (!column.naturalKey().equals(naturalKey)) {
                add(Code.INVALID_NATURAL_KEY, path + ".relationshipId");
            }
        }

        /** A column object that belongs to {@code relation} (by its natural key), or null after a violation. */
        private Column column(UUID columnId, Relation relation, String path) {
            if (columnId == null || !columnNames.containsKey(columnId)) {
                add(Code.UNKNOWN_REFERENCE, path);
                return null;
            }
            NaturalKey.ColumnName name = columnNames.get(columnId);
            if (!name.relationName().equals(relation.name())) {
                add(Code.UNKNOWN_REFERENCE, path);
                return null;
            }
            String type = columnObjects.get(columnId).dataType();
            return new Column(relation.index(), columnId, name, type, TypeCategory.of(type));
        }

        private void safety() {
            AggregationSafety expected = plan.joins().isEmpty() ? AggregationSafety.SINGLE_OBJECT
                    : AggregationSafety.JOINS_TO_PRIMARY_KEY_ONLY;
            if (plan.safety() != expected) {
                add(Code.INCONSISTENT_SAFETY, "plan.safety");
            }
        }

        // --- aggregations and grain ---

        private List<Measure> measures(Relation base) {
            List<Measure> measures = new ArrayList<>();
            Set<UUID> metricIds = new HashSet<>();
            for (int i = 0; i < plan.aggregations().size(); i++) {
                PlanAggregation a = plan.aggregations().get(i);
                String path = "plan.aggregations[" + i + "]";
                if (a.order() == null || a.metricId() == null || a.key() == null || a.key().isBlank()
                        || a.aggregation() == null) {
                    add(Code.MISSING_FIELD, path);
                    continue;
                }
                if (a.order() != i + 1) {
                    add(Code.INVALID_ORDER, path + ".order");
                }
                if (!metricIds.add(a.metricId())) {
                    add(Code.DUPLICATE_REFERENCE, path + ".metricId");
                }
                DefinitionSnapshot d = definitions.get(a.metricId());
                if (d == null || d.kind() != DefinitionKind.METRIC) {
                    add(Code.UNKNOWN_REFERENCE, path + ".metricId");
                    continue;
                }
                if (d.aggregation() != a.aggregation() || !Objects.equals(d.columnObjectId(), a.columnObjectId())
                        || !a.key().equals(d.key())) {
                    add(Code.INCONSISTENT_DEFINITION, path);
                }
                Column column = null;
                if (a.columnObjectId() != null) {
                    if (!columnNames.containsKey(a.columnObjectId())) {
                        add(Code.UNKNOWN_REFERENCE, path + ".columnObjectId");
                        continue;
                    }
                    // D33: metrics aggregate rows of the base object only.
                    if (!columnNames.get(a.columnObjectId()).relationName().equals(base.name())) {
                        add(Code.INCONSISTENT_GRAIN, path + ".columnObjectId");
                        continue;
                    }
                    column = column(a.columnObjectId(), base, path + ".columnObjectId");
                    if (column == null) {
                        continue;
                    }
                    if (!TypeCategory.executable(column.declaredType())) {
                        add(Code.UNSUPPORTED_TYPE, path + ".columnObjectId");
                    }
                }
                if (a.aggregation() != Aggregation.COUNT
                        && (column == null || column.category() != ValueType.NUMERIC)) {
                    add(Code.UNSUPPORTED_TYPE, path + ".aggregation");
                }
                measures.add(new Measure(a.key(), a.aggregation(), column));
            }
            return measures;
        }

        private List<Dimension> dimensions(List<Measure> measures) {
            List<Dimension> dimensions = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            measures.forEach(m -> {
                if (!keys.add(m.key())) {
                    add(Code.DUPLICATE_REFERENCE, "plan.aggregations");
                }
            });
            Set<UUID> dimensionIds = new HashSet<>();
            for (int i = 0; i < plan.outputGrain().size(); i++) {
                GroupingKey g = plan.outputGrain().get(i);
                String path = "plan.outputGrain[" + i + "]";
                if (g.dimensionId() == null || g.key() == null || g.key().isBlank() || g.objectId() == null
                        || g.columnObjectId() == null || g.type() == null || g.valueType() == null) {
                    add(Code.MISSING_FIELD, path);
                    continue;
                }
                if (!dimensionIds.add(g.dimensionId())) {
                    add(Code.DUPLICATE_REFERENCE, path + ".dimensionId");
                }
                if (!keys.add(g.key())) {
                    add(Code.DUPLICATE_REFERENCE, path + ".key");
                }
                Relation relation = aliases.get(g.objectId());
                if (relation == null) {
                    add(Code.UNKNOWN_REFERENCE, path + ".objectId");
                    continue;
                }
                Column column = column(g.columnObjectId(), relation, path + ".columnObjectId");
                if (column == null) {
                    continue;
                }
                DefinitionSnapshot d = definitions.get(g.dimensionId());
                if (d == null || d.kind() != DefinitionKind.DIMENSION) {
                    add(Code.UNKNOWN_REFERENCE, path + ".dimensionId");
                    continue;
                }
                if (!g.columnObjectId().equals(d.columnObjectId()) || d.dimensionType() != g.type()
                        || !g.key().equals(d.key())) {
                    add(Code.INCONSISTENT_DEFINITION, path);
                }
                if (g.valueType() != column.category()) {
                    add(Code.INCONSISTENT_DEFINITION, path + ".valueType");
                }
                if (!TypeCategory.executable(column.declaredType())) {
                    add(Code.UNSUPPORTED_TYPE, path + ".columnObjectId");
                    continue;
                }
                ZoneId zone = null;
                if (g.type() == DimensionType.TEMPORAL) {
                    if (!TEMPORAL.contains(g.valueType())) {
                        add(Code.UNSUPPORTED_TYPE, path + ".valueType");
                        continue;
                    }
                    if (g.granularity() == null) {
                        add(Code.MISSING_FIELD, path + ".granularity");
                        continue;
                    }
                    if (d.granularities() != null && !d.granularities().isEmpty()
                            && !d.granularities().contains(g.granularity())) {
                        add(Code.INCONSISTENT_DEFINITION, path + ".granularity");
                    }
                    if (g.valueType() == ValueType.TIMESTAMP_TZ) {
                        zone = zone(g.timeZone(), path + ".timeZone");
                        if (!Objects.equals(g.timeZone(), d.timeZone())) {
                            add(Code.INCONSISTENT_DEFINITION, path + ".timeZone");
                        }
                    } else if (g.timeZone() != null) {
                        // Semantic only allows a zone on timestamptz columns; a zone elsewhere has no defined meaning.
                        add(Code.INVALID_TIME_ZONE, path + ".timeZone");
                    }
                } else if (g.granularity() != null || g.timeZone() != null) {
                    add(Code.INCONSISTENT_DEFINITION, path);
                }
                dimensions.add(new Dimension(g.key(), column, g.type(), g.granularity(), zone));
            }
            return dimensions;
        }

        private ZoneId zone(String timeZone, String path) {
            if (timeZone == null || timeZone.isBlank()) {
                add(Code.INVALID_TIME_ZONE, path);
                return null;
            }
            try {
                ZoneId zone = ZoneId.of(timeZone);
                // IANA region identifiers (or UTC) only. Fixed offsets are refused: PostgreSQL reads '+02:00' in
                // AT TIME ZONE with POSIX sign conventions (the opposite of ISO-8601), which would silently shift
                // every bucket.
                if (zone instanceof ZoneOffset || !zone.getId().equals(timeZone)) {
                    add(Code.INVALID_TIME_ZONE, path);
                    return null;
                }
                return zone;
            } catch (DateTimeException e) {
                add(Code.INVALID_TIME_ZONE, path);
                return null;
            }
        }

        // --- filters ---

        private List<RowFilter> rowFilters() {
            List<RowFilter> filters = new ArrayList<>();
            for (int i = 0; i < plan.preAggregationFilters().size(); i++) {
                PlanFilter f = plan.preAggregationFilters().get(i);
                String path = "plan.preAggregationFilters[" + i + "]";
                if (f.order() == null || f.definitionId() == null || f.definitionKind() == null
                        || f.operator() == null || f.valueType() == null || f.objectId() == null
                        || f.columnObjectId() == null || f.values() == null) {
                    add(Code.MISSING_FIELD, path);
                    continue;
                }
                if (f.order() != i + 1) {
                    add(Code.INVALID_ORDER, path + ".order");
                }
                if (f.definitionKind() != DefinitionKind.DIMENSION) {
                    add(Code.UNSUPPORTED_OPERATOR, path + ".definitionKind");
                    continue;
                }
                DefinitionSnapshot d = definitions.get(f.definitionId());
                if (d == null || d.kind() != DefinitionKind.DIMENSION) {
                    add(Code.UNKNOWN_REFERENCE, path + ".definitionId");
                    continue;
                }
                if (!f.columnObjectId().equals(d.columnObjectId())) {
                    add(Code.INCONSISTENT_DEFINITION, path + ".columnObjectId");
                }
                Relation relation = aliases.get(f.objectId());
                if (relation == null) {
                    add(Code.UNKNOWN_REFERENCE, path + ".objectId");
                    continue;
                }
                if (optionalObjects.contains(f.objectId())) {
                    // A row filter on the optional side of a LEFT JOIN would silently turn it into an INNER JOIN.
                    add(Code.UNSAFE_FILTER, path + ".objectId");
                    continue;
                }
                Column column = column(f.columnObjectId(), relation, path + ".columnObjectId");
                if (column == null) {
                    continue;
                }
                if (f.valueType() != column.category()) {
                    add(Code.INCONSISTENT_DEFINITION, path + ".valueType");
                    continue;
                }
                if (!TypeCategory.executable(column.declaredType())) {
                    add(Code.UNSUPPORTED_TYPE, path + ".columnObjectId");
                    continue;
                }
                List<FilterValue> values = values(f.operator(), f.valueType(), f.values(), path);
                if (values != null) {
                    filters.add(new RowFilter(column, f.operator(), f.valueType(), values));
                }
            }
            return filters;
        }

        private List<GroupFilter> groupFilters() {
            List<GroupFilter> filters = new ArrayList<>();
            for (int i = 0; i < plan.postAggregationFilters().size(); i++) {
                PlanFilter f = plan.postAggregationFilters().get(i);
                String path = "plan.postAggregationFilters[" + i + "]";
                if (f.order() == null || f.definitionId() == null || f.definitionKind() == null
                        || f.operator() == null || f.valueType() == null || f.values() == null) {
                    add(Code.MISSING_FIELD, path);
                    continue;
                }
                if (f.order() != i + 1) {
                    add(Code.INVALID_ORDER, path + ".order");
                }
                if (f.definitionKind() != DefinitionKind.METRIC || f.objectId() != null) {
                    add(Code.UNSUPPORTED_OPERATOR, path + ".definitionKind");
                    continue;
                }
                int measure = -1;
                for (int m = 0; m < plan.aggregations().size(); m++) {
                    if (f.definitionId().equals(plan.aggregations().get(m).metricId())) {
                        measure = m;
                    }
                }
                if (measure < 0) {
                    add(Code.UNKNOWN_REFERENCE, path + ".definitionId");
                    continue;
                }
                if (!Objects.equals(f.columnObjectId(), plan.aggregations().get(measure).columnObjectId())) {
                    add(Code.INCONSISTENT_DEFINITION, path + ".columnObjectId");
                }
                if (!GROUP_OPERATORS.contains(f.operator())) {
                    add(Code.UNSUPPORTED_OPERATOR, path + ".operator");
                    continue;
                }
                if (f.valueType() != ValueType.NUMERIC) {
                    add(Code.UNSUPPORTED_TYPE, path + ".valueType");
                    continue;
                }
                List<FilterValue> values = values(f.operator(), f.valueType(), f.values(), path);
                if (values != null) {
                    filters.add(new GroupFilter(measure, f.operator(), ((FilterValue.Number) values.get(0)).value()));
                }
            }
            return filters;
        }

        private List<FilterValue> values(FilterOperator operator, ValueType type, List<String> raw, String path) {
            if (type == ValueType.OTHER) {
                add(Code.UNSUPPORTED_TYPE, path + ".valueType");
                return null;
            }
            int count = raw.size();
            boolean arityOk = switch (operator) {
                case IS_NULL, IS_NOT_NULL -> count == 0;
                case IN -> count >= 1 && count <= MAX_IN_VALUES;
                default -> count == 1;
            };
            if (!arityOk) {
                add(operator == FilterOperator.IN && count > MAX_IN_VALUES ? Code.COMPLEXITY_LIMIT_EXCEEDED
                        : Code.INVALID_FILTER_VALUE, path + ".values");
                return null;
            }
            if (switch (operator) {
                case GT, GTE, LT, LTE -> !ORDERED.contains(type);
                case IN -> type == ValueType.BOOLEAN;
                default -> false;
            }) {
                add(Code.UNSUPPORTED_OPERATOR, path + ".operator");
                return null;
            }
            List<FilterValue> values = new ArrayList<>();
            Set<FilterValue> distinct = new HashSet<>();
            for (int v = 0; v < raw.size(); v++) {
                FilterValue value = parse(type, raw.get(v));
                if (value == null) {
                    add(Code.INVALID_FILTER_VALUE, path + ".values[" + v + "]");
                    return null;
                }
                if (!distinct.add(value)) {
                    add(Code.DUPLICATE_REFERENCE, path + ".values[" + v + "]");
                    return null;
                }
                values.add(value);
            }
            return values;
        }

        /** Strict parse of a canonical value; null if it is not valid for the type. */
        private static FilterValue parse(ValueType type, String text) {
            if (text == null) {
                return null;
            }
            try {
                return switch (type) {
                    case TEXT -> text.indexOf('\0') >= 0 ? null : new FilterValue.Text(text);
                    case NUMERIC -> new FilterValue.Number(new BigDecimal(text));
                    case BOOLEAN -> "true".equals(text) ? new FilterValue.Bool(true)
                            : "false".equals(text) ? new FilterValue.Bool(false) : null;
                    case DATE -> new FilterValue.Date(LocalDate.parse(text));
                    case TIMESTAMP -> new FilterValue.Timestamp(LocalDateTime.parse(text));
                    case TIMESTAMP_TZ -> new FilterValue.Instant(OffsetDateTime.parse(text)
                            .withOffsetSameInstant(ZoneOffset.UTC));
                    case TIME -> new FilterValue.Time(LocalTime.parse(text));
                    case UUID -> CANONICAL_UUID.matcher(text).matches()
                            ? new FilterValue.Uuid(java.util.UUID.fromString(text)) : null;
                    case OTHER -> null;
                };
            } catch (RuntimeException e) {
                return null;
            }
        }

        // --- operations ---

        /** The operation list must be exactly the one implied by the plan (Semantic S4.2 order). */
        private void operations() {
            List<PlanOperation> expected = new ArrayList<>();
            expected.add(new PlanOperation(1, OperationKind.SCAN, List.of()));
            for (int i = 0; i < plan.joins().size(); i++) {
                expected.add(new PlanOperation(expected.size() + 1, OperationKind.JOIN, List.of(i + 1)));
            }
            if (!plan.preAggregationFilters().isEmpty()) {
                expected.add(new PlanOperation(expected.size() + 1, OperationKind.FILTER_ROWS,
                        orders(plan.preAggregationFilters().size())));
            }
            expected.add(new PlanOperation(expected.size() + 1, OperationKind.GROUP_AND_AGGREGATE, List.of()));
            if (!plan.postAggregationFilters().isEmpty()) {
                expected.add(new PlanOperation(expected.size() + 1, OperationKind.FILTER_GROUPS,
                        orders(plan.postAggregationFilters().size())));
            }
            if (!expected.equals(plan.operations())) {
                add(Code.INCONSISTENT_OPERATIONS, "plan.operations");
            }
        }

        private static List<Integer> orders(int size) {
            List<Integer> orders = new ArrayList<>();
            for (int i = 1; i <= size; i++) {
                orders.add(i);
            }
            return orders;
        }

        // --- helpers ---

        private void required(Object value, String path) {
            if (value == null) {
                add(Code.MISSING_FIELD, path);
            }
        }

        private void noNullElements(List<?> list, String path) {
            if (list != null && list.stream().anyMatch(Objects::isNull)) {
                add(Code.MISSING_FIELD, path);
            }
        }

        private void add(Code code, String path) {
            violations.add(new PlanViolation(code, path));
        }

        private void failIfAny() {
            if (!violations.isEmpty()) {
                throw new PlanNotExecutableException(violations);
            }
        }
    }
}
