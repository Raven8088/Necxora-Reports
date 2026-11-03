package com.nexora.reports.execution.domain;

import java.util.Objects;

/**
 * A4-B10 (R4-23, D-B10-13): an unexpected failure as it may be written to the logs. The message of an exception of
 * unknown origin can carry anything (a value read from a data source, a fragment of a document, a body returned by
 * another service), so it is withheld; the diagnostic information stays: the class of every exception in the cause
 * chain and every stack frame. The causes are redacted the same way; suppressed exceptions are dropped.
 */
public final class RedactedFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final int MAX_CAUSES = 16;

    private RedactedFailure(Throwable original, int depth) {
        super("message withheld; " + original.getClass().getName(), cause(original, depth), false, true);
        setStackTrace(original.getStackTrace());
    }

    /** The redacted copy of {@code failure}; the original is not modified. */
    public static RedactedFailure of(Throwable failure) {
        return new RedactedFailure(Objects.requireNonNull(failure, "failure"), 0);
    }

    private static RedactedFailure cause(Throwable original, int depth) {
        Throwable cause = original.getCause();
        return cause == null || cause == original || depth >= MAX_CAUSES ? null : new RedactedFailure(cause, depth + 1);
    }
}
