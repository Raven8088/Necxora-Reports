package com.nexora.reports.translation.domain;

import java.util.Objects;

/**
 * A typed JDBC parameter. The SQL text always contains an explicit {@code CAST(? AS <type>)} matching
 * {@link #type()}, so PostgreSQL never infers the type of a value from its content.
 *
 * @param value one of {@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate}, {@code LocalDateTime},
 *              {@code OffsetDateTime}, {@code LocalTime}, {@code UUID} or {@code Long}, matching {@code type}
 */
public record SqlParameter(SqlType type, Object value) {

    public enum SqlType {
        TEXT("text"),
        NUMERIC("numeric"),
        BOOLEAN("boolean"),
        DATE("date"),
        TIMESTAMP("timestamp"),
        TIMESTAMP_TZ("timestamptz"),
        TIME("time"),
        UUID("uuid"),
        BIGINT("bigint");

        private final String sqlName;

        SqlType(String sqlName) {
            this.sqlName = sqlName;
        }

        public String sqlName() {
            return sqlName;
        }
    }

    public SqlParameter {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    /** Never prints the value: parameters may contain business data. */
    @Override
    public String toString() {
        return "SqlParameter[" + type + "]";
    }
}
