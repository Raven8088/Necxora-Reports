package com.nexora.reports.run.domain;

/** The closed list of internal failures of a run (FAILED). They are not refusals: nothing was wrong with the request. */
public enum FailureCode {
    RENDER_FAILED("render_failed"),
    STORAGE_FAILED("storage_failed"),
    INTERNAL_ERROR("internal_error");

    private final String code;

    FailureCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static FailureCode of(String code) {
        for (FailureCode f : values()) {
            if (f.code.equals(code)) {
                return f;
            }
        }
        throw new IllegalArgumentException("unknown failure code");
    }
}
