package com.nexora.reports.plan2.domain;

import java.util.List;

/** The report plan, its specification or one of its logical plans cannot be executed. Fails closed. */
public class PlanV2Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<PlanV2Violation> violations;

    public PlanV2Exception(List<PlanV2Violation> violations) {
        super(message(violations));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("at least one violation is required");
        }
        this.violations = List.copyOf(violations);
    }

    public static PlanV2Exception of(String rule, PlanV2Violation.Kind kind, String path) {
        return new PlanV2Exception(List.of(new PlanV2Violation(rule, kind, path)));
    }

    public List<PlanV2Violation> violations() {
        return violations;
    }

    public PlanV2Violation first() {
        return violations.getFirst();
    }

    private static String message(List<PlanV2Violation> violations) {
        return violations.isEmpty() ? "no violations"
                : violations.getFirst().rule() + " at " + violations.getFirst().path()
                + (violations.size() > 1 ? " (+" + (violations.size() - 1) + " more)" : "");
    }
}
