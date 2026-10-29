package com.nexora.reports.translation.domain;

import com.nexora.reports.plan.domain.LogicalPlan.ValueType;
import com.nexora.reports.plan.domain.NaturalKey.RelationName;

import java.util.List;

/**
 * What the live PostgreSQL schema must look like for the query to mean what the plan says. Semantic checks
 * validity against its imported catalog, not against the live database, so the executor verifies these facts in
 * the same read-only transaction, just before running the query, and stops on any difference.
 *
 * @param relations   every relation read by the query
 * @param columns     every column read, with the type category the plan relies on
 * @param primaryKeys for every joined relation, the exact set of columns that must be its primary key (this is
 *                    what proves that the join cannot multiply rows)
 */
public record SchemaExpectation(List<RelationName> relations, List<ExpectedColumn> columns,
                                List<ExpectedPrimaryKey> primaryKeys) {

    public SchemaExpectation {
        relations = List.copyOf(relations);
        columns = List.copyOf(columns);
        primaryKeys = List.copyOf(primaryKeys);
    }

    public record ExpectedColumn(RelationName relation, String column, String declaredType, ValueType category) {
    }

    public record ExpectedPrimaryKey(RelationName relation, List<String> columns) {
        public ExpectedPrimaryKey {
            columns = List.copyOf(columns);
        }
    }
}
