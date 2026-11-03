package com.nexora.reports.translation2.domain;

import com.nexora.reports.plan.domain.NaturalKey;
import com.nexora.reports.plan.domain.NaturalKey.RelationName;
import com.nexora.reports.plan2.domain.LogicalPlanV2;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Aggregate;
import com.nexora.reports.plan2.domain.LogicalPlanV2.ColumnRef;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Filter;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Join;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Limit;
import com.nexora.reports.plan2.domain.LogicalPlanV2.ObjectType;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Operation;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Order;
import com.nexora.reports.plan2.domain.LogicalPlanV2.PlanObject;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Project;
import com.nexora.reports.plan2.domain.LogicalPlanV2.Scan;
import com.nexora.reports.plan2.domain.PlanV2Exception;
import com.nexora.reports.plan2.domain.PlanV2Violation;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Pure, deterministic translation of one validated dataset ({@code nexora-logical-plan/2}, profile {@code basic-a})
 * into one parameterized PostgreSQL SELECT. It never executes anything and never reads a catalog: the same plan and
 * row limit always give the same text and parameters. It knows no table, column or business concept: everything
 * comes from the plan (PR-1, PR-2), so plans of any shape within {@code basic-a} go through the same code.
 * <p>
 * Shape of the query:
 * <pre>
 * SELECT * FROM (
 *   SELECT &lt;outputs, in output order, aliased "c1".."cN"&gt;
 *     FROM "schema"."relation" AS "t0"
 *     LEFT|INNER JOIN "schema"."relation" AS "t1" ON "t0"."a" = "t1"."b" [AND ...]   -- in operation order
 *    WHERE &lt;predicates, joined with AND&gt;
 *    GROUP BY &lt;group columns&gt;                                                   -- AGGREGATE with groups
 * ) AS "q"
 * ORDER BY "q"."cK" [COLLATE "C"] ASC|DESC NULLS LAST|FIRST, ...                   -- omitted without keys
 * LIMIT CAST(? AS bigint)                                                          -- maxRows + 1
 * </pre>
 * The order is applied on the outputs of the inner query because PostgreSQL does not accept {@code COLLATE} on an
 * output name; the tie-breaker of the plan is already among its keys, so the order is total.
 * <p>
 * Rules: identifiers come only from the plan's natural keys, quoted with embedded quotes doubled, and must be
 * non-empty, without NUL and at most 63 bytes (PostgreSQL would truncate longer names silently); a column must belong
 * to the relation its natural key names; aliases, output ids, labels and other texts of the plan never appear in the
 * SQL; every value is a parameter wrapped in {@code CAST(? AS type)}, typed by the category of its column; NULL
 * follows SQL three-valued logic (as in R1). The result is checked by {@link SqlTextGuard}.
 */
public final class PlanV2Translator {

    private static final String IDENTIFIER = "IDENTIFIER";

    public SqlQueryV2 translate(ValidatedReportPlan.Dataset dataset) {
        return translate(dataset.plan(), dataset.maxRows());
    }

    /** @param maxRows effective row limit of the dataset; the query asks for one more row to detect truncation */
    public SqlQueryV2 translate(LogicalPlanV2 plan, int maxRows) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        return new Build(plan).query(maxRows);
    }

    private static final class Build {

        private final LogicalPlanV2 plan;
        private final Map<UUID, PlanObject> objects = new HashMap<>();
        private final Map<String, String> sqlAlias = new LinkedHashMap<>();
        private final Map<String, PlanObject> relationOfAlias = new LinkedHashMap<>();
        private final List<SqlParameter> parameters = new ArrayList<>();
        private final List<SchemaRequirementsV2.Column> columns = new ArrayList<>();
        private final List<SchemaRequirementsV2.PrimaryKey> primaryKeys = new ArrayList<>();

        Build(LogicalPlanV2 plan) {
            this.plan = plan;
            plan.objects().forEach(o -> objects.put(o.id(), o));
        }

        SqlQueryV2 query(int maxRows) {
            StringBuilder from = new StringBuilder();
            StringJoiner where = new StringJoiner("\n   AND ");
            List<String> select = new ArrayList<>();
            List<String> groupBy = new ArrayList<>();
            Order order = null;
            for (Operation operation : plan.operations()) {
                switch (operation) {
                    case Scan scan -> {
                        PlanObject base = relation(scan.object(), "scan");
                        alias(scan.alias(), base);
                        from.append("\n  FROM ").append(relationSql(base)).append(" AS ")
                                .append(quote(sqlAlias.get(scan.alias())));
                    }
                    case Join join -> from.append(join(join));
                    case Filter filter -> filter.predicates().forEach(p -> where.add(predicate(p)));
                    case Aggregate aggregate -> {
                        for (LogicalPlanV2.GroupKey group : aggregate.groupBy()) {
                            String column = column(group.column());
                            select.add(column);
                            groupBy.add(column);
                        }
                        for (LogicalPlanV2.Measure measure : aggregate.measures()) {
                            if (!"COUNT".equals(measure.aggregation())) {
                                throw unsupported("measure");
                            }
                            select.add(measure.column() == null ? "COUNT(*)"
                                    : "COUNT(" + column(measure.column()) + ")");
                        }
                    }
                    case Project project -> project.columns().forEach(c -> select.add(column(c.column())));
                    case Order o -> order = o;
                    case Limit limit -> {
                        // the effective limit (maxRows) already includes the plan's LIMIT (PlanV2Validator)
                    }
                }
            }
            if (select.size() != plan.output().size() || order == null) {
                throw unsupported("output");
            }
            Map<String, Integer> position = new HashMap<>();
            List<SqlQueryV2.Output> outputs = new ArrayList<>();
            StringJoiner selectSql = new StringJoiner(",\n         ", "SELECT * FROM (\n  SELECT ", "");
            for (int i = 0; i < select.size(); i++) {
                LogicalPlanV2.OutputColumn out = plan.output().get(i);
                position.put(out.output(), i + 1);
                outputs.add(new SqlQueryV2.Output(i + 1, out.output(), out.valueType(), out.role(), out.hidden()));
                selectSql.add(select.get(i) + " AS " + quote("c" + (i + 1)));
            }
            StringBuilder sql = new StringBuilder(selectSql.toString()).append(from);
            if (where.length() > 0) {
                sql.append("\n WHERE ").append(where);
            }
            if (!groupBy.isEmpty()) {
                sql.append("\n GROUP BY ").append(String.join(", ", groupBy));
            }
            sql.append("\n) AS ").append(quote("q"));
            sql.append(orderBy(order, position));
            sql.append("\nLIMIT ").append(parameter(SqlType.BIGINT, (long) maxRows + 1));
            if (order != null && "UNIQUE_KEY".equals(order.tieBreaker())) {
                PlanObject base = relationOfAlias.values().iterator().next();
                primaryKey(base);
            }
            String text = sql.toString();
            SqlTextGuard.check(text);
            List<RelationName> relations = relationOfAlias.values().stream().map(this::relationName).toList();
            return new SqlQueryV2(plan.datasetId(), text, parameters, outputs, maxRows,
                    new SchemaRequirementsV2(relations, distinct(columns), primaryKeys));
        }

        private String join(Join join) {
            if (join.conditions().isEmpty()) {
                throw unsupported("join");
            }
            UUID joinedId = objects.get(join.conditions().getFirst().right().column()) == null ? null
                    : objects.get(join.conditions().getFirst().right().column()).parentId();
            PlanObject joined = relation(joinedId, "join");
            alias(join.alias(), joined);
            StringJoiner on = new StringJoiner(" AND ");
            for (LogicalPlanV2.JoinCondition condition : join.conditions()) {
                on.add(column(condition.left()) + " = " + column(condition.right()));
            }
            primaryKey(joined);
            String type = switch (join.type()) {
                case "LEFT" -> "LEFT JOIN";
                case "INNER" -> "INNER JOIN";
                default -> throw unsupported("join.type");
            };
            return "\n  " + type + " " + relationSql(joined) + " AS " + quote(sqlAlias.get(join.alias())) + " ON " + on;
        }

        private String predicate(LogicalPlanV2.Predicate p) {
            String column = column(p.column());
            LogicalPlanV2.Category category = objects.get(p.column().column()).typeCategory();
            return switch (p.operator()) {
                case "IS_NULL" -> column + " IS NULL";
                case "IS_NOT_NULL" -> column + " IS NOT NULL";
                case "IN" -> {
                    StringJoiner values = new StringJoiner(", ", column + " IN (", ")");
                    p.values().forEach(v -> values.add(literal(v, category)));
                    yield values.toString();
                }
                case "EQ" -> column + " = " + literal(p.values().getFirst(), category);
                case "NE" -> column + " <> " + literal(p.values().getFirst(), category);
                case "GT" -> column + " > " + literal(p.values().getFirst(), category);
                case "GTE" -> column + " >= " + literal(p.values().getFirst(), category);
                case "LT" -> column + " < " + literal(p.values().getFirst(), category);
                case "LTE" -> column + " <= " + literal(p.values().getFirst(), category);
                default -> throw unsupported("filter.operator");
            };
        }

        private String orderBy(Order order, Map<String, Integer> position) {
            if (order.keys().isEmpty()) {
                return "";
            }
            StringJoiner keys = new StringJoiner(", ", "\nORDER BY ", "");
            for (LogicalPlanV2.OrderKey key : order.keys()) {
                Integer p = position.get(key.output());
                if (p == null) {
                    throw unsupported("order");
                }
                String collation = key.collation() == null ? "" : " COLLATE " + quote(key.collation());
                String direction = switch (key.direction()) {
                    case "ASC" -> " ASC";
                    case "DESC" -> " DESC";
                    default -> throw unsupported("order.direction");
                };
                String nulls = switch (key.nulls()) {
                    case "LAST" -> " NULLS LAST";
                    case "FIRST" -> " NULLS FIRST";
                    default -> throw unsupported("order.nulls");
                };
                keys.add(quote("q") + "." + quote("c" + p) + collation + direction + nulls);
            }
            return keys.toString();
        }

        /** {@code "tN"."column"}, after checking that the column belongs to the alias's relation. */
        private String column(ColumnRef ref) {
            PlanObject relation = relationOfAlias.get(ref.alias());
            PlanObject column = objects.get(ref.column());
            if (relation == null || column == null || column.type() != ObjectType.COLUMN
                    || !relation.id().equals(column.parentId())) {
                throw unsupported("column");
            }
            LogicalPlanV2.NaturalKey key = column.naturalKey();
            LogicalPlanV2.NaturalKey parent = relation.naturalKey();
            if (!key.schema().equals(parent.schema()) || !key.name().equals(parent.name())) {
                throw PlanV2Exception.of(IDENTIFIER, PlanV2Violation.Kind.PLAN_NOT_SUPPORTED, path("column"));
            }
            columns.add(new SchemaRequirementsV2.Column(relationName(relation), identifier(key.column()),
                    column.typeCategory()));
            return quote(sqlAlias.get(ref.alias())) + "." + quote(identifier(key.column()));
        }

        private PlanObject relation(UUID id, String what) {
            PlanObject relation = id == null ? null : objects.get(id);
            if (relation == null || (relation.type() != ObjectType.TABLE && relation.type() != ObjectType.VIEW)) {
                throw unsupported(what);
            }
            return relation;
        }

        private void alias(String planAlias, PlanObject relation) {
            if (sqlAlias.containsKey(planAlias) || relationOfAlias.containsValue(relation)) {
                throw unsupported("alias");
            }
            sqlAlias.put(planAlias, "t" + sqlAlias.size());
            relationOfAlias.put(planAlias, relation);
        }

        private String relationSql(PlanObject relation) {
            RelationName name = relationName(relation);
            return quote(name.schema()) + "." + quote(name.name());
        }

        private RelationName relationName(PlanObject relation) {
            return new RelationName(identifier(relation.naturalKey().schema()),
                    identifier(relation.naturalKey().name()));
        }

        private void primaryKey(PlanObject relation) {
            List<UUID> keyColumns = relation.uniqueKeys().stream().filter(k -> "PRIMARY_KEY".equals(k.kind()))
                    .findFirst().orElseThrow(() -> unsupported("primaryKey")).columns();
            List<String> names = new ArrayList<>();
            for (UUID id : keyColumns) {
                PlanObject column = objects.get(id);
                if (column == null || !relation.id().equals(column.parentId())) {
                    throw unsupported("primaryKey");
                }
                names.add(identifier(column.naturalKey().column()));
            }
            SchemaRequirementsV2.PrimaryKey key = new SchemaRequirementsV2.PrimaryKey(relationName(relation), names);
            if (!primaryKeys.contains(key)) {
                primaryKeys.add(key);
            }
        }

        /** A value of the plan as a typed parameter; the SQL type comes from the column category, not the literal. */
        private String literal(LogicalPlanV2.Literal literal, LogicalPlanV2.Category category) {
            String v = literal.value();
            return switch (category) {
                case NUMERIC -> parameter(SqlType.NUMERIC, new BigDecimal(v));
                case TEXT -> parameter(SqlType.TEXT, v);
                case UUID -> parameter(SqlType.UUID, UUID.fromString(v));
                case BOOLEAN -> parameter(SqlType.BOOLEAN, Boolean.valueOf("true".equals(v)));
                case DATE -> parameter(SqlType.DATE, LocalDate.parse(v));
                case TIMESTAMP -> parameter(SqlType.TIMESTAMP, LocalDateTime.parse(v));
                case TIMESTAMP_TZ -> parameter(SqlType.TIMESTAMP_TZ, OffsetDateTime.parse(v));
                case TIME, OTHER -> throw unsupported("filter.category");
            };
        }

        private String parameter(SqlType type, Object value) {
            parameters.add(new SqlParameter(type, value));
            return "CAST(? AS " + type.sqlName() + ")";
        }

        private String identifier(String name) {
            if (name == null || name.isEmpty() || name.indexOf('\0') >= 0
                    || name.getBytes(StandardCharsets.UTF_8).length > NaturalKey.MAX_IDENTIFIER_BYTES) {
                throw PlanV2Exception.of(IDENTIFIER, PlanV2Violation.Kind.PLAN_NOT_SUPPORTED, path("identifier"));
            }
            return name;
        }

        private PlanV2Exception unsupported(String what) {
            return PlanV2Exception.of("BASIC-A", PlanV2Violation.Kind.PLAN_NOT_SUPPORTED, path(what));
        }

        private String path(String what) {
            return "logical-plan[" + plan.datasetId() + "]." + what;
        }

        private static String quote(String identifier) {
            return NaturalKey.quote(identifier);
        }

        private static <T> List<T> distinct(List<T> values) {
            return values.stream().distinct().toList();
        }
    }
}
