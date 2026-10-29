package com.nexora.reports.execution.domain;

import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.PlanValidity.Issue;
import com.nexora.reports.plan.domain.PlanValidity.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidityPolicyTest {

    private final ValidityPolicy policy = new ValidityPolicy();
    private final UUID planId = UUID.randomUUID();

    @Test
    void currentIsExecutableWithoutWarnings() {
        var decision = policy.decide(planId, new PlanValidity(planId, Status.CURRENT, List.of()));
        assertThat(decision.status()).isEqualTo(Status.CURRENT);
        assertThat(decision.warnings()).isEmpty();
    }

    @Test
    void currentWithOnlyNonBlockingChangesIsExecutableWithAWarning() {
        var decision = policy.decide(planId, new PlanValidity(planId, Status.CURRENT_WITH_CHANGES,
                List.of(issue("PHYSICAL_OBJECT_CHANGED", false))));
        assertThat(decision.status()).isEqualTo(Status.CURRENT_WITH_CHANGES);
        assertThat(decision.warnings()).extracting(ExecutionWarning::code)
                .containsExactly(ExecutionWarning.Code.PLAN_CURRENT_WITH_CHANGES);
    }

    @Test
    void staleIsRefused() {
        assertCode(new PlanValidity(planId, Status.STALE, List.of(issue("RELATIONSHIP_NOT_USABLE", true))),
                ErrorCode.PLAN_STALE);
    }

    @Test
    void anythingInconsistentOrUnclassifiedIsUnconfirmed() {
        assertCode(new PlanValidity(planId, Status.CURRENT_WITH_CHANGES,
                List.of(issue("PHYSICAL_OBJECT_CHANGED", false), issue("DEFINITION_CHANGED", true))),
                ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        assertCode(new PlanValidity(planId, Status.CURRENT_WITH_CHANGES, List.of()),
                ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        assertCode(new PlanValidity(planId, Status.CURRENT_WITH_CHANGES,
                List.of(new Issue("PHYSICAL_OBJECT_CHANGED", null, "PHYSICAL_OBJECT", UUID.randomUUID(), "m"))),
                ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        assertCode(new PlanValidity(planId, Status.CURRENT, List.of(issue("PHYSICAL_OBJECT_CHANGED", false))),
                ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        assertCode(new PlanValidity(UUID.randomUUID(), Status.CURRENT, List.of()),
                ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        assertCode(new PlanValidity(planId, null, List.of()), ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        assertCode(null, ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
    }

    private void assertCode(PlanValidity validity, ErrorCode code) {
        assertThatThrownBy(() -> policy.decide(planId, validity)).isInstanceOfSatisfying(ExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private static Issue issue(String code, boolean blocking) {
        return new Issue(code, blocking, "PHYSICAL_OBJECT", UUID.randomUUID(), "message");
    }
}
