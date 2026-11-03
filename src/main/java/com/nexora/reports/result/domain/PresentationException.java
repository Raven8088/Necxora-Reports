package com.nexora.reports.result.domain;

import java.util.Objects;

/**
 * A presentation that could not be produced (A4-B7). No artifact exists and nothing was delivered. The run service
 * (B9) turns the code into the {@code refusal} of {@code nexora-report-run/1}; the message is internal.
 */
public class PresentationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** The artifact would exceed, or exceeded while being written, its size limit. */
        ARTIFACT_TOO_LARGE("artifact_too_large"),
        /** No presentation slot became free in time. */
        TOO_MANY_PRESENTATIONS("too_many_executions"),
        /** Writing failed (I/O): nothing was delivered. */
        RENDER_FAILED("render_failed");

        private final String refusalCode;

        Code(String refusalCode) {
            this.refusalCode = refusalCode;
        }

        public String refusalCode() {
            return refusalCode;
        }
    }

    private final Code code;

    public PresentationException(Code code, String internalMessage) {
        super(internalMessage);
        this.code = Objects.requireNonNull(code, "code");
    }

    public PresentationException(Code code, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
