package com.nexora.reports.translation.domain;

/**
 * One column of the tabular result, in SELECT order. {@code name} is the definition key from the plan; it never
 * appears in the SQL text.
 */
public record ResultColumn(String name, Role role, OutputType type) {

    public enum Role { DIMENSION, METRIC }

    /**
     * JSON representation of the values: {@code INTEGER} as a JSON number; {@code NUMERIC} as a decimal string
     * (exact, no binary floating point); temporal values as ISO-8601 strings ({@code TIMESTAMP_TZ} in UTC);
     * {@code BOOLEAN} as a JSON boolean; {@code TEXT}, {@code TIME} and {@code UUID} as strings. NULL is JSON null.
     */
    public enum OutputType { TEXT, NUMERIC, INTEGER, BOOLEAN, DATE, TIMESTAMP, TIMESTAMP_TZ, TIME, UUID }
}
