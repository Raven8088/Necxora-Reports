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
        /** A4-B4: the rows of a {@code /2} execution exceed its row or memory budget; nothing is returned. */
        RESULT_TOO_LARGE(true),
        /** A4-B4: a source value cannot be represented exactly in its output type (NaN, infinity, fraction in INTEGER). */
        VALUE_NOT_REPRESENTABLE(true),
        /** A4-B5: the tenancy of the data source could not be verified (P-1); nothing was read. */
        TENANT_SCOPE_UNVERIFIED(true),
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
        AUDIT_UNAVAILABLE(false),
        /** A4-B9: no run with that id for this tenant, data source and owner (indistinguishable from none). */
        RUN_NOT_FOUND(true),
        /** A4-B9: the run has no outcome yet and is within its deadline. */
        RUN_IN_PROGRESS(true),
        /** A4-B9: the run was recorded as FAILED (an internal error); it has no report-run document. */
        RUN_RECORDED_AS_FAILED(true),
        /** A4-B9: the run ended without an outcome before its deadline and was closed as INTERRUPTED. */
        RUN_INTERRUPTED(true),
        /** A4-B9: the run failed internally while it was being executed now; it is recorded as FAILED. */
        RUN_FAILED(false),
        /**
         * A4-B9: the run was refused because the report plan itself is not valid, so its fingerprint is unknown and
         * no {@code nexora-report-run/1} (which requires it) can describe the refusal.
         */
        REPORT_PLAN_INVALID(true),
        /** A4-B9: the artifact expired or was purged. */
        ARTIFACT_GONE(true),
        /** A4-B9: the stored bytes do not match their metadata; nothing is delivered. */
        ARTIFACT_INTEGRITY_FAILED(false),
        /** A4-B9 (B8-O3): no download slot is free. */
        TOO_MANY_DOWNLOADS(true),
        /** A4-B9: Reports' own run registry could not record or read the run. */
        RUN_STORAGE_UNAVAILABLE(false),
        /**
         * B10-O3 (D-O3-3): the destination of the data source resolved right before the read is not the one that was
         * configured when the run started; nothing was read.
         */
        DATA_SOURCE_CONFIGURATION_CHANGED(true);

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
