package com.nexora.reports.execution.domain;

import java.util.Objects;

/** A non-sensitive warning attached to a successful execution (codes and fixed messages only). */
public record ExecutionWarning(Code code, String message) {

    public enum Code {
        /** Semantic classified the plan as CURRENT_WITH_CHANGES: only non-blocking catalog changes. */
        PLAN_CURRENT_WITH_CHANGES,
        /** The result has more rows than the limit; only the first ones are returned. */
        RESULT_TRUNCATED
    }

    public ExecutionWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
