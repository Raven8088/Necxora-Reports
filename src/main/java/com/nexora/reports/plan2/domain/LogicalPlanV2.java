package com.nexora.reports.plan2.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A validated {@code nexora-logical-plan/2} (one dataset), restricted to profile {@code basic-a}. Built only by
 * {@link PlanV2Validator}, after the schema, the contract rules, the profile, the identities and the fingerprints
 * have been checked. Enumerated values are kept as the contract strings (already validated against the schema).
 * <p>
 * Reports knows no table, column or business concept: everything comes from this document (PR-1, PR-2).
 */
public record LogicalPlanV2(UUID tenantId, UUID dataSourceId, UUID metadataSnapshotId, String specFingerprint,
                            String datasetId, List<PlanObject> objects, List<Definition> definitions,
                            List<Relationship> relationships, List<Operation> operations, List<OutputColumn> output,
                            String fingerprint) {

    public static final String FORMAT = "nexora-logical-plan/2";

    public LogicalPlanV2 {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(datasetId, "datasetId");
        objects = List.copyOf(objects);
        definitions = List.copyOf(definitions);
        relationships = List.copyOf(relationships);
        operations = List.copyOf(operations);
        output = List.copyOf(output);
    }

    public Optional<PlanObject> object(UUID id) {
        return objects.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public Optional<Relationship> relationship(UUID id) {
        return relationships.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** Category of a column, as declared by Semantic; verified again against the live database before reading. */
    public enum Category { NUMERIC, TEXT, BOOLEAN, DATE, TIMESTAMP, TIMESTAMP_TZ, TIME, UUID, OTHER }

    public enum ObjectType { TABLE, VIEW, COLUMN }

    /** {@code column} is null for tables and views. */
    public record NaturalKey(String schema, String name, String column) {
    }

    public record UniqueKey(String kind, List<UUID> columns) {
        public UniqueKey {
            columns = List.copyOf(columns);
        }
    }

    /** {@code parentId}, {@code typeCategory} and {@code nullable} are null for tables and views. */
    public record PlanObject(UUID id, ObjectType type, NaturalKey naturalKey, UUID parentId, Category typeCategory,
                             Boolean nullable, List<UniqueKey> uniqueKeys) {
        public PlanObject {
            uniqueKeys = List.copyOf(uniqueKeys);
        }
    }

    /** GOVERNED or DERIVED definition; metadata for the methodology, never changes the SQL. */
    public record Definition(UUID id, String kind, String origin, String key, Integer revision) {
    }

    public record Pair(UUID source, UUID target) {
    }

    public record Relationship(UUID id, String origin, String status, List<Pair> pairs, String targetUniqueness,
                               List<String> allowedJoinTypes) {
        public Relationship {
            pairs = List.copyOf(pairs);
            allowedJoinTypes = List.copyOf(allowedJoinTypes);
        }
    }

    public record ColumnRef(String alias, UUID column) {
    }

    public sealed interface Operation permits Scan, Join, Filter, Aggregate, Project, Order, Limit {
    }

    public record Scan(String alias, UUID object) implements Operation {
    }

    /** In {@code basic-a} every condition is column = column and the joined side is exactly a primary key. */
    public record Join(String alias, String from, UUID relationship, String direction, String type,
                       String uniqueness, List<JoinCondition> conditions) implements Operation {
        public Join {
            conditions = List.copyOf(conditions);
        }
    }

    public record JoinCondition(ColumnRef left, ColumnRef right) {
    }

    public record Filter(List<Predicate> predicates) implements Operation {
        public Filter {
            predicates = List.copyOf(predicates);
        }
    }

    public record Predicate(ColumnRef column, String operator, List<Literal> values) {
        public Predicate {
            values = List.copyOf(values);
        }
    }

    /** Typed literal in canonical text form (numbers without trailing zeros, instants in UTC). */
    public record Literal(String type, String value) {
    }

    public record Aggregate(List<GroupKey> groupBy, List<Measure> measures) implements Operation {
        public Aggregate {
            groupBy = List.copyOf(groupBy);
            measures = List.copyOf(measures);
        }
    }

    public record GroupKey(String output, ColumnRef column) {
    }

    /** {@code column} is null for {@code COUNT(*)}. */
    public record Measure(String output, String aggregation, ColumnRef column) {
    }

    public record Project(List<ProjectedColumn> columns) implements Operation {
        public Project {
            columns = List.copyOf(columns);
        }
    }

    public record ProjectedColumn(String output, ColumnRef column) {
    }

    public record Order(List<OrderKey> keys, String tieBreaker, List<String> tieBreakerOutputs) implements Operation {
        public Order {
            keys = List.copyOf(keys);
            tieBreakerOutputs = List.copyOf(tieBreakerOutputs);
        }
    }

    /** {@code collation} is {@code "C"} for text keys and null otherwise. */
    public record OrderKey(String output, String direction, String nulls, String collation) {
    }

    /** Rows requested by the plan; Reports reads one more to detect truncation. */
    public record Limit(int maxRows) implements Operation {
    }

    public record OutputColumn(String output, String label, String valueType, String role, boolean hidden,
                               String origin) {
    }
}
