package com.nexora.reports.translation.domain;

import com.nexora.reports.plan.domain.FilterValue;
import com.nexora.reports.plan.domain.LogicalPlan.FilterOperator;
import com.nexora.reports.plan.domain.LogicalPlan.TimeGranularity;
import com.nexora.reports.plan.domain.LogicalPlan.ValueType;
import com.nexora.reports.plan.domain.NaturalKey;
import com.nexora.reports.plan.domain.ValidatedPlan;
import com.nexora.reports.plan.domain.ValidatedPlan.Column;
import com.nexora.reports.plan.domain.ValidatedPlan.Dimension;
import com.nexora.reports.plan.domain.ValidatedPlan.Equality;
import com.nexora.reports.plan.domain.ValidatedPlan.GroupFilter;
import com.nexora.reports.plan.domain.ValidatedPlan.Join;
import com.nexora.reports.plan.domain.ValidatedPlan.Measure;
import com.nexora.reports.plan.domain.ValidatedPlan.Relation;
import com.nexora.reports.plan.domain.ValidatedPlan.RowFilter;
import com.nexora.reports.translation.domain.ResultColumn.OutputType;
import com.nexora.reports.translation.domain.ResultColumn.Role;
import com.nexora.reports.translation.domain.SchemaExpectation.ExpectedColumn;
import com.nexora.reports.translation.domain.SchemaExpectation.ExpectedPrimaryKey;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Pure, deterministic translation of a {@link ValidatedPlan} into one parameterized PostgreSQL SELECT. It never
 * executes anything and never reads the catalog: the same plan and limit always give the same text and parameters.
 * <p>
 * Shape of the query (the logical order of {@code nexora-logical-plan/1}):
 * <pre>
 * SELECT &lt;grouping keys&gt;, &lt;aggregations&gt;
 *   FROM &lt;base&gt; AS "t0"
 *   [LEFT|INNER] JOIN &lt;relation&gt; AS "tN" ON "tP"."a" = "tN"."b" [AND ...]   -- in join order
 *  WHERE &lt;row filters&gt;                                                      -- pre-aggregation
 *  GROUP BY 1, 2, ...                                                          -- output grain
 * HAVING &lt;group filters&gt;                                                    -- post-aggregation
 *  ORDER BY 1, 2, ... NULLS LAST                                               -- deterministic truncation
 *  LIMIT ?
 * </pre>
 * Semantics fixed by R1 (documented in docs/ARCHITECTURE.md):
 * <ul>
 *   <li>Identifiers come only from the plan's natural keys and are quoted with doubled embedded quotes; result
 *       columns are read by position, so definition keys never appear in SQL.</li>
 *   <li>Every value is a JDBC parameter wrapped in an explicit {@code CAST(? AS type)}.</li>
 *   <li>NULL follows SQL three-valued logic: {@code EQ}, {@code NE}, ordering operators and {@code IN} never match
 *       NULL (use {@code IS_NULL}); a NULL grouping value forms its own group.</li>
 *   <li>{@code COUNT} without column counts rows; {@code COUNT(col)} counts non-null values; {@code SUM} and
 *       {@code AVG} ignore NULL. Without grouping keys and without rows the result is one row with {@code COUNT}
 *       0 and {@code SUM}/{@code AVG} NULL; with grouping keys it has no rows. No {@code COALESCE} is added.</li>
 *   <li>Temporal buckets are returned as the {@code date} of the first day of the bucket. {@code WEEK} is the ISO
 *       week starting on Monday ({@code date_trunc('week')}). A {@code timestamptz} column is converted to the
 *       dimension's IANA zone ({@code AT TIME ZONE}) before truncation; {@code timestamp} and {@code date}
 *       columns are truncated as stored (wall-clock), with no zone conversion.</li>
 *   <li>Joins never use {@code DISTINCT}: the validator only admits joins to a side unique by primary key, and
 *       the executor re-checks that primary key in the live database.</li>
 * </ul>
 */
public final class QueryTranslator {

    public SqlQuery translate(ValidatedPlan plan, int maxRows) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        List<SqlParameter> parameters = new ArrayList<>();
        List<ResultColumn> columns = new ArrayList<>();
        StringJoiner select = new StringJoiner(", ");
        for (Dimension d : plan.dimensions()) {
            select.add(dimension(d, parameters));
            columns.add(new ResultColumn(d.key(), Role.DIMENSION, dimensionType(d)));
        }
        for (Measure m : plan.measures()) {
            select.add(measure(m));
            columns.add(new ResultColumn(m.key(), Role.METRIC, measureType(m)));
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(select);
        Relation base = plan.relations().get(0);
        sql.append("\nFROM ").append(relation(base));
        for (Join join : plan.joins()) {
            sql.append('\n').append(switch (join.type()) {
                case INNER -> "INNER JOIN ";
                case LEFT -> "LEFT JOIN ";
            }).append(relation(join.target())).append(" ON ");
            StringJoiner on = new StringJoiner(" AND ");
            for (Equality e : join.conditions()) {
                on.add(column(e.left()) + " = " + column(e.right()));
            }
            sql.append(on);
        }
        if (!plan.rowFilters().isEmpty()) {
            StringJoiner where = new StringJoiner("\n  AND ");
            for (RowFilter f : plan.rowFilters()) {
                where.add(rowFilter(f, parameters));
            }
            sql.append("\nWHERE ").append(where);
        }
        if (!plan.dimensions().isEmpty()) {
            sql.append("\nGROUP BY ").append(ordinals(plan.dimensions().size(), ""));
        }
        if (!plan.groupFilters().isEmpty()) {
            StringJoiner having = new StringJoiner("\n  AND ");
            for (GroupFilter f : plan.groupFilters()) {
                having.add(measure(plan.measures().get(f.measureIndex())) + " " + comparison(f.operator()) + " "
                        + parameter(SqlType.NUMERIC, f.value(), parameters));
            }
            sql.append("\nHAVING ").append(having);
        }
        if (!plan.dimensions().isEmpty()) {
            sql.append("\nORDER BY ").append(ordinals(plan.dimensions().size(), " NULLS LAST"));
        }
        sql.append("\nLIMIT ").append(parameter(SqlType.BIGINT, (long) maxRows + 1, parameters));

        String text = sql.toString();
        assertSingleStatement(text);
        return new SqlQuery(text, parameters, columns, maxRows, schema(plan));
    }

    /**
     * Defense in depth: outside quoted identifiers the text may contain no statement separator and no string
     * literal other than the fixed granularity units; NUL is forbidden everywhere. (Quoted identifiers may legally
     * contain any character except NUL, and their embedded quotes are doubled.)
     */
    static void assertSingleStatement(String sql) {
        boolean inIdentifier = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\0') {
                throw new IllegalStateException("translated SQL contains NUL");
            }
            if (ch == '"') {
                if (inIdentifier && i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    i++;
                    continue;
                }
                inIdentifier = !inIdentifier;
                continue;
            }
            if (inIdentifier) {
                continue;
            }
            if (ch == ';' || (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-')
                    || (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*')) {
                throw new IllegalStateException("translated SQL contains a separator or comment");
            }
            if (ch == '\'') {
                int end = sql.indexOf('\'', i + 1);
                String literal = end < 0 ? "" : sql.substring(i + 1, end);
                if (!UNITS.contains(literal)) {
                    throw new IllegalStateException("translated SQL contains an unexpected literal");
                }
                i = end;
            }
        }
        if (inIdentifier) {
            throw new IllegalStateException("translated SQL has an unterminated identifier");
        }
    }

    private static final java.util.Set<String> UNITS = java.util.Set.of("day", "week", "month", "quarter", "year");

    // --- expressions ---

    private static String relation(Relation relation) {
        return NaturalKey.quote(relation.name().schema()) + "." + NaturalKey.quote(relation.name().name())
                + " AS " + alias(relation.index());
    }

    private static String alias(int index) {
        return "\"t" + index + "\"";
    }

    private static String column(Column column) {
        return alias(column.relationIndex()) + "." + NaturalKey.quote(column.name().column());
    }

    private static String dimension(Dimension d, List<SqlParameter> parameters) {
        String column = column(d.column());
        return switch (d.type()) {
            case CATEGORICAL -> column;
            case TEMPORAL -> {
                String unit = unit(d.granularity());
                String source = switch (d.column().category()) {
                    case TIMESTAMP_TZ -> column + " AT TIME ZONE "
                            + parameter(SqlType.TEXT, d.timeZone().getId(), parameters);
                    case TIMESTAMP -> column;
                    case DATE -> "CAST(" + column + " AS timestamp)";
                    default -> throw new IllegalStateException("not a temporal column");
                };
                yield "CAST(date_trunc('" + unit + "', " + source + ") AS date)";
            }
        };
    }

    /** Fixed literals from a closed enum: never data from the request. */
    private static String unit(TimeGranularity granularity) {
        return switch (granularity) {
            case DAY -> "day";
            case WEEK -> "week";
            case MONTH -> "month";
            case QUARTER -> "quarter";
            case YEAR -> "year";
        };
    }

    private static String measure(Measure m) {
        return switch (m.aggregation()) {
            case COUNT -> m.column() == null ? "COUNT(*)" : "COUNT(" + column(m.column()) + ")";
            case SUM -> "SUM(" + column(m.column()) + ")";
            case AVG -> "AVG(" + column(m.column()) + ")";
        };
    }

    private static String rowFilter(RowFilter f, List<SqlParameter> parameters) {
        String column = column(f.column());
        SqlType type = sqlType(f.valueType());
        return switch (f.operator()) {
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case IN -> {
                StringJoiner list = new StringJoiner(", ", column + " IN (", ")");
                f.values().forEach(v -> list.add(parameter(type, value(v), parameters)));
                yield list.toString();
            }
            default -> column + " " + comparison(f.operator()) + " "
                    + parameter(type, value(f.values().get(0)), parameters);
        };
    }

    private static String comparison(FilterOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> throw new IllegalStateException("not a comparison: " + operator);
        };
    }

    private static String parameter(SqlType type, Object value, List<SqlParameter> parameters) {
        parameters.add(new SqlParameter(type, value));
        return "CAST(? AS " + type.sqlName() + ")";
    }

    private static Object value(FilterValue value) {
        return switch (value) {
            case FilterValue.Text v -> v.value();
            case FilterValue.Number v -> v.value();
            case FilterValue.Bool v -> v.value();
            case FilterValue.Date v -> v.value();
            case FilterValue.Timestamp v -> v.value();
            case FilterValue.Instant v -> v.value();
            case FilterValue.Time v -> v.value();
            case FilterValue.Uuid v -> v.value();
        };
    }

    private static SqlType sqlType(ValueType type) {
        return switch (type) {
            case NUMERIC -> SqlType.NUMERIC;
            case TEXT -> SqlType.TEXT;
            case BOOLEAN -> SqlType.BOOLEAN;
            case DATE -> SqlType.DATE;
            case TIMESTAMP -> SqlType.TIMESTAMP;
            case TIMESTAMP_TZ -> SqlType.TIMESTAMP_TZ;
            case TIME -> SqlType.TIME;
            case UUID -> SqlType.UUID;
            case OTHER -> throw new IllegalStateException("unsupported type reached the translator");
        };
    }

    private static String ordinals(int count, String suffix) {
        StringJoiner ordinals = new StringJoiner(", ");
        for (int i = 1; i <= count; i++) {
            ordinals.add(i + suffix);
        }
        return ordinals.toString();
    }

    // --- result types ---

    private static OutputType dimensionType(Dimension d) {
        if (d.type() == com.nexora.reports.plan.domain.LogicalPlan.DimensionType.TEMPORAL) {
            return OutputType.DATE;
        }
        return switch (d.column().category()) {
            case NUMERIC -> OutputType.NUMERIC;
            case TEXT -> OutputType.TEXT;
            case BOOLEAN -> OutputType.BOOLEAN;
            case DATE -> OutputType.DATE;
            case TIMESTAMP -> OutputType.TIMESTAMP;
            case TIMESTAMP_TZ -> OutputType.TIMESTAMP_TZ;
            case TIME -> OutputType.TIME;
            case UUID -> OutputType.UUID;
            case OTHER -> throw new IllegalStateException("unsupported type reached the translator");
        };
    }

    private static OutputType measureType(Measure m) {
        return m.aggregation() == com.nexora.reports.plan.domain.LogicalPlan.Aggregation.COUNT
                ? OutputType.INTEGER : OutputType.NUMERIC;
    }

    // --- schema expectations ---

    private static SchemaExpectation schema(ValidatedPlan plan) {
        List<ExpectedColumn> columns = plan.referencedColumns().stream()
                .map(c -> new ExpectedColumn(c.name().relationName(), c.name().column(), c.declaredType(),
                        c.category()))
                .toList();
        List<ExpectedPrimaryKey> keys = plan.joins().stream()
                .map(j -> new ExpectedPrimaryKey(j.target().name(), j.targetKeyColumns()))
                .toList();
        return new SchemaExpectation(plan.relations().stream().map(Relation::name).toList(), columns, keys);
    }
}
