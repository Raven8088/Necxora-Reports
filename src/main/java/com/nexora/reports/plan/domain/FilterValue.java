package com.nexora.reports.plan.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A filter value parsed from its canonical text, typed by the plan's {@code valueType}. It is always data: it is
 * bound as a typed JDBC parameter and never becomes part of the SQL text.
 */
public sealed interface FilterValue {

    record Text(String value) implements FilterValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    record Number(BigDecimal value) implements FilterValue {
        public Number {
            Objects.requireNonNull(value, "value");
        }
    }

    record Bool(boolean value) implements FilterValue {
    }

    record Date(LocalDate value) implements FilterValue {
        public Date {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Wall-clock value for {@code timestamp without time zone}. */
    record Timestamp(LocalDateTime value) implements FilterValue {
        public Timestamp {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Instant (normalized to UTC) for {@code timestamp with time zone}. */
    record Instant(OffsetDateTime value) implements FilterValue {
        public Instant {
            Objects.requireNonNull(value, "value");
        }
    }

    record Time(LocalTime value) implements FilterValue {
        public Time {
            Objects.requireNonNull(value, "value");
        }
    }

    record Uuid(UUID value) implements FilterValue {
        public Uuid {
            Objects.requireNonNull(value, "value");
        }
    }
}
