package com.nexora.reports.execution.domain;

/**
 * How a result column of a {@code /2} query is read ({@code valueType} of {@code nexora-logical-plan/2}), and the
 * exact Java type of its values: never text for numbers, dates or instants (A4 proposal v3, section 4 bis.2).
 */
public enum OutputValueType {
    /** {@link String}. */
    TEXT,
    /** {@link java.math.BigDecimal}, exact. */
    NUMBER,
    /** {@link Long}; a value with a fractional part or out of range is refused. */
    INTEGER,
    /** {@link Boolean}. */
    BOOLEAN,
    /** {@link java.time.LocalDate}. */
    DATE,
    /** {@link java.time.LocalDateTime}. */
    TIMESTAMP,
    /** {@link java.time.OffsetDateTime} in UTC. */
    TIMESTAMP_TZ;

    /** Fails closed on any value outside the contract. */
    public static OutputValueType of(String valueType) {
        for (OutputValueType type : values()) {
            if (type.name().equals(valueType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported value type");
    }
}
