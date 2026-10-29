package com.nexora.reports.plan.domain;

import com.nexora.reports.plan.domain.LogicalPlan.Aggregation;
import com.nexora.reports.plan.domain.LogicalPlan.DimensionType;
import com.nexora.reports.plan.domain.LogicalPlan.FilterOperator;
import com.nexora.reports.plan.domain.LogicalPlan.JoinType;
import com.nexora.reports.plan.domain.LogicalPlan.TimeGranularity;
import com.nexora.reports.plan.domain.LogicalPlan.ValueType;
import com.nexora.reports.plan.domain.NaturalKey.ColumnName;
import com.nexora.reports.plan.domain.NaturalKey.RelationName;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * A plan that passed every check of {@link PlanValidator}, reduced to what the translator needs. Relations are
 * numbered in join order (0 is the base object), so each one gets exactly one alias; every column is resolved to
 * its relation, its exact identifiers and its declared type. Nothing here is inferred or repaired: every element
 * comes from the authorized plan.
 */
public record ValidatedPlan(
        UUID planId,
        UUID tenantId,
        UUID dataSourceId,
        String fingerprint,
        List<Relation> relations,
        List<Join> joins,
        List<Dimension> dimensions,
        List<Measure> measures,
        List<RowFilter> rowFilters,
        List<GroupFilter> groupFilters) {

    public ValidatedPlan {
        relations = List.copyOf(relations);
        joins = List.copyOf(joins);
        dimensions = List.copyOf(dimensions);
        measures = List.copyOf(measures);
        rowFilters = List.copyOf(rowFilters);
        groupFilters = List.copyOf(groupFilters);
    }

    /** @param index 0 for the base object, then 1..n in join order */
    public record Relation(int index, UUID objectId, RelationName name) {
    }

    /** A column of one of the plan's relations, with the type observed by Discovery. */
    public record Column(int relationIndex, UUID objectId, ColumnName name, String declaredType, ValueType category) {
    }

    /**
     * @param target           the relation joined (its index is the join order)
     * @param conditions       equalities {@code left = right}, left on an already joined relation
     * @param targetKeyColumns columns of {@code target} that the plan states are exactly its primary key
     */
    public record Join(int order, JoinType type, Relation target, List<Equality> conditions,
                       List<String> targetKeyColumns) {
        public Join {
            conditions = List.copyOf(conditions);
            targetKeyColumns = List.copyOf(targetKeyColumns);
        }
    }

    public record Equality(Column left, Column right) {
    }

    /** @param timeZone only for TEMPORAL dimensions over {@code timestamp with time zone} columns */
    public record Dimension(String key, Column column, DimensionType type, TimeGranularity granularity,
                            ZoneId timeZone) {
    }

    /** @param column null for {@code COUNT} of rows */
    public record Measure(String key, Aggregation aggregation, Column column) {
    }

    public record RowFilter(Column column, FilterOperator operator, ValueType valueType, List<FilterValue> values) {
        public RowFilter {
            values = List.copyOf(values);
        }
    }

    /** A filter on an aggregated value; {@code measureIndex} points into {@link #measures()}. */
    public record GroupFilter(int measureIndex, FilterOperator operator, BigDecimal value) {
    }

    /** Every column the query reads, for the live schema check. */
    public List<Column> referencedColumns() {
        java.util.LinkedHashMap<UUID, Column> columns = new java.util.LinkedHashMap<>();
        joins.forEach(j -> j.conditions().forEach(c -> {
            columns.putIfAbsent(c.left().objectId(), c.left());
            columns.putIfAbsent(c.right().objectId(), c.right());
        }));
        dimensions.forEach(d -> columns.putIfAbsent(d.column().objectId(), d.column()));
        measures.stream().filter(m -> m.column() != null)
                .forEach(m -> columns.putIfAbsent(m.column().objectId(), m.column()));
        rowFilters.forEach(f -> columns.putIfAbsent(f.column().objectId(), f.column()));
        return List.copyOf(columns.values());
    }
}
