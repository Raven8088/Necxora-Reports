package com.nexora.reports.execution.domain;

import java.util.Objects;

/**
 * A controlled failure of an execution. The {@link ErrorCode} is what clients and the audit see; the message is an
 * internal reason for the logs only and never contains filter values, SQL, credentials or connection details.
 */
public class ExecutionException extends RuntimeException {

    /** Stable error codes. {@link #rejection()} distinguishes refusals from infrastructure failures. */
    public enum ErrorCode {
        REQUESTER_NOT_ALLOWED(true),
        DATA_SOURCE_NOT_FOUND(true),
        DATA_SOURCE_NOT_EXECUTABLE(true),
        DATA_SOURCE_NOT_CONFIGURED(true),
        PLAN_NOT_FOUND(true),
        PLAN_STALE(true),
        PLAN_CHANGED_DURING_EXECUTION(true),
        PHYSICAL_SCHEMA_MISMATCH(true),
        TOO_MANY_EXECUTIONS(true),
        PLAN_VALIDITY_UNCONFIRMED(false),
        SEMANTIC_UNAVAILABLE(false),
        SEMANTIC_CONTRACT_VIOLATION(false),
        SERVICE_IDENTITY_INVALID(false),
        DATA_SOURCE_UNAVAILABLE(false),
        DATA_SOURCE_TARGET_NOT_ALLOWED(false),
        /** The configured database role is not read-only (superuser, write privileges or no read-only session). */
        DATA_SOURCE_ROLE_NOT_READ_ONLY(false),
        EXECUTION_TIMEOUT(false),
        EXECUTION_FAILED(false),
        AUDIT_UNAVAILABLE(false);

        private final boolean rejection;

        ErrorCode(boolean rejection) {
            this.rejection = rejection;
        }

        public boolean rejection() {
            return rejection;
        }
    }

    private final ErrorCode code;

    public ExecutionException(ErrorCode code, String internalReason) {
        super(internalReason);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ExecutionException(ErrorCode code, String internalReason, Throwable cause) {
        super(internalReason, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
