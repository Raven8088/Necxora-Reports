package com.nexora.reports.result.domain;

import java.util.Objects;

/**
 * One methodology or header datum (A4 proposal v3, section 4 bis.7): either known, with its value, or unavailable,
 * with a stable reason code that the presentation translates. Nothing is ever filled in with an assumption.
 */
public sealed interface Fact {

    record Known(String value) implements Fact {
        public Known {
            Objects.requireNonNull(value, "value");
        }
    }

    /** @param reason stable code, for example {@code relationship_approver_not_in_plan} */
    record Unavailable(String reason) implements Fact {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static Fact known(String value) {
        return new Known(value);
    }

    static Fact unavailable(String reason) {
        return new Unavailable(reason);
    }
}
