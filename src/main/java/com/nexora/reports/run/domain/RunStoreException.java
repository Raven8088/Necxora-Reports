package com.nexora.reports.run.domain;

import java.util.Objects;

/** A run could not be recorded at all. Never carries business data. */
public class RunStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** No started run with that id in that tenant and source. */
        UNKNOWN_RUN,
        /** A run with that id was already started. */
        ALREADY_STARTED,
        /** Reports' database could not record even the outcome; the reconciliation will close the run. */
        STORAGE_UNAVAILABLE
    }

    private final Code code;

    public RunStoreException(Code code, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
