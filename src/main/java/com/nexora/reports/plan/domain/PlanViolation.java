package com.nexora.reports.plan.domain;

import java.util.Objects;

/**
 * One reason why a plan cannot be executed. {@code path} points into the plan document (for example
 * {@code plan.joins[0].multiplicationRisk}); it never contains filter values or other business data.
 */
public record PlanViolation(Code code, String path) {

    public enum Code {
        /** The document could not be read as {@code nexora-logical-plan/1} (unknown field, enum value, type...). */
        UNREADABLE_DOCUMENT,
        UNSUPPORTED_FORMAT_VERSION,
        MISSING_FIELD,
        INCONSISTENT_FINGERPRINT,
        INVALID_NATURAL_KEY,
        /** A referenced object, column, definition or relationship is not part of the plan. */
        UNKNOWN_REFERENCE,
        DUPLICATE_REFERENCE,
        INVALID_ORDER,
        JOIN_NOT_AUTHORIZED,
        MULTIPLICATION_RISK,
        UNIQUENESS_NOT_PROVEN,
        JOIN_CONDITION_MISMATCH,
        UNSAFE_FILTER,
        INCONSISTENT_GRAIN,
        INCONSISTENT_DEFINITION,
        INCONSISTENT_SAFETY,
        INCONSISTENT_OPERATIONS,
        UNSUPPORTED_TYPE,
        UNSUPPORTED_OPERATOR,
        INVALID_FILTER_VALUE,
        INVALID_TIME_ZONE,
        COMPLEXITY_LIMIT_EXCEEDED
    }

    public PlanViolation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
    }
}
