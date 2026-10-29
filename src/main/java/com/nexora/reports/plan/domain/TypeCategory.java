package com.nexora.reports.plan.domain;

import com.nexora.reports.plan.domain.LogicalPlan.ValueType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Category of a PostgreSQL type name, with the same mapping Semantic uses to classify columns (Discovery reports
 * {@code udt}-style names such as {@code int8} or {@code timestamptz}; long SQL names are accepted too). Unknown
 * types are {@link ValueType#OTHER}, which R1 never executes (fail closed).
 * <p>
 * Additionally, R1 refuses some types that Semantic classifies but whose SQL semantics differ from the category:
 * {@code money} (no {@code AVG}, no comparison with numeric) and {@code timetz} (comparison with a wall-clock time
 * depends on offsets).
 */
public final class TypeCategory {

    private static final Map<String, ValueType> POSTGRESQL = Map.ofEntries(
            Map.entry("int2", ValueType.NUMERIC), Map.entry("int4", ValueType.NUMERIC),
            Map.entry("int8", ValueType.NUMERIC), Map.entry("smallint", ValueType.NUMERIC),
            Map.entry("integer", ValueType.NUMERIC), Map.entry("bigint", ValueType.NUMERIC),
            Map.entry("serial", ValueType.NUMERIC), Map.entry("smallserial", ValueType.NUMERIC),
            Map.entry("bigserial", ValueType.NUMERIC), Map.entry("serial2", ValueType.NUMERIC),
            Map.entry("serial4", ValueType.NUMERIC), Map.entry("serial8", ValueType.NUMERIC),
            Map.entry("numeric", ValueType.NUMERIC), Map.entry("decimal", ValueType.NUMERIC),
            Map.entry("float4", ValueType.NUMERIC), Map.entry("float8", ValueType.NUMERIC),
            Map.entry("real", ValueType.NUMERIC), Map.entry("double precision", ValueType.NUMERIC),
            Map.entry("money", ValueType.NUMERIC),
            Map.entry("varchar", ValueType.TEXT), Map.entry("bpchar", ValueType.TEXT), Map.entry("char", ValueType.TEXT),
            Map.entry("text", ValueType.TEXT), Map.entry("name", ValueType.TEXT), Map.entry("citext", ValueType.TEXT),
            Map.entry("character varying", ValueType.TEXT), Map.entry("character", ValueType.TEXT),
            Map.entry("bool", ValueType.BOOLEAN), Map.entry("boolean", ValueType.BOOLEAN),
            Map.entry("date", ValueType.DATE),
            Map.entry("timestamp", ValueType.TIMESTAMP),
            Map.entry("timestamp without time zone", ValueType.TIMESTAMP),
            Map.entry("timestamptz", ValueType.TIMESTAMP_TZ),
            Map.entry("timestamp with time zone", ValueType.TIMESTAMP_TZ),
            Map.entry("time", ValueType.TIME), Map.entry("timetz", ValueType.TIME),
            Map.entry("uuid", ValueType.UUID));

    /** Types R1 refuses even though they have a category. */
    private static final Set<String> NOT_EXECUTABLE = Set.of("money", "timetz");

    private TypeCategory() {
    }

    public static ValueType of(String dataType) {
        if (dataType == null) {
            return ValueType.OTHER;
        }
        return POSTGRESQL.getOrDefault(normalize(dataType), ValueType.OTHER);
    }

    /** Whether R1 can execute operations on a column of this declared type. */
    public static boolean executable(String dataType) {
        return of(dataType) != ValueType.OTHER && !NOT_EXECUTABLE.contains(normalize(dataType));
    }

    private static String normalize(String dataType) {
        return dataType.strip().toLowerCase(Locale.ROOT);
    }
}
