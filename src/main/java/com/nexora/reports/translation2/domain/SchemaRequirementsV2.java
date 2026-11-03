package com.nexora.reports.translation2.domain;

import com.nexora.reports.plan.domain.NaturalKey.RelationName;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Category;

import java.util.List;

/**
 * What the live PostgreSQL schema must look like for a translated query to mean what its plan says. Semantic checks
 * validity against its imported catalog, not against the live database; the executor checks these facts in the same
 * read-only transaction, before reading, and refuses on any difference. {@code nexora-logical-plan/2} carries the
 * category of each column, not its declared type, so the category is what is verified (A4 proposal v3, section 2).
 *
 * @param relations   every relation read, in alias order
 * @param columns     every column read, with the category the plan relies on
 * @param primaryKeys the exact primary key of every joined relation (proves a join cannot multiply rows) and of the
 *                    base relation when it is the tie-breaker of the order (proves the order is total)
 */
public record SchemaRequirementsV2(List<RelationName> relations, List<Column> columns,
                                   List<PrimaryKey> primaryKeys) {

    public SchemaRequirementsV2 {
        relations = List.copyOf(relations);
        columns = List.copyOf(columns);
        primaryKeys = List.copyOf(primaryKeys);
    }

    public record Column(RelationName relation, String column, Category category) {
    }

    public record PrimaryKey(RelationName relation, List<String> columns) {
        public PrimaryKey {
            columns = List.copyOf(columns);
        }
    }
}
