package com.nexora.reports.result.domain;

import java.util.Objects;

/**
 * The validated documents and the rows cannot be assembled into a report (A4-B6). The run service (B9) turns the
 * code into the {@code refusal} of {@code nexora-report-run/1}; the message is internal and never contains values.
 */
public class ResultAssemblyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** The specification is not the one the report plan was validated with (its fingerprint differs). */
        SPEC_MISMATCH("spec_mismatch"),
        /** Sections, items or totals do not correspond to the datasets (D-A4-7): {@code report_layout_invalid}. */
        REPORT_LAYOUT_INVALID("report_layout_invalid"),
        /** The report would exceed its cell budget, presentation included: {@code budget_exceeded}. */
        BUDGET_EXCEEDED("budget_exceeded");

        private final String refusalCode;

        Code(String refusalCode) {
            this.refusalCode = refusalCode;
        }

        /** The code of {@code nexora-report-run/1}'s refusal. */
        public String refusalCode() {
            return refusalCode;
        }
    }

    private final Code code;

    public ResultAssemblyException(Code code, String internalMessage) {
        super(internalMessage);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
