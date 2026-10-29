package com.nexora.reports.plan.domain;

import java.util.List;

/** The authorized plan cannot be executed safely by R1. Nothing is executed and the plan is never modified. */
public class PlanNotExecutableException extends RuntimeException {

    private final transient List<PlanViolation> violations;

    public PlanNotExecutableException(List<PlanViolation> violations) {
        super("plan not executable: " + violations.stream().map(v -> v.code().name()).distinct().toList());
        this.violations = List.copyOf(violations);
    }

    public static PlanNotExecutableException of(PlanViolation.Code code, String path) {
        return new PlanNotExecutableException(List.of(new PlanViolation(code, path)));
    }

    public List<PlanViolation> violations() {
        return violations;
    }
}
