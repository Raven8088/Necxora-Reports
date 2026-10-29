package com.nexora.reports.execution.domain;

import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.PlanValidity.Issue;
import com.nexora.reports.plan.domain.PlanValidity.Status;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * R1 policy on plan validity (fail closed):
 * <ul>
 *   <li>{@code CURRENT} with no issues: executable;</li>
 *   <li>{@code CURRENT_WITH_CHANGES}: executable only if every issue is present and explicitly classified by
 *       Semantic as non-blocking; the result and the audit carry a warning;</li>
 *   <li>{@code STALE}: refused;</li>
 *   <li>anything Semantic cannot confirm or that is internally inconsistent (another plan id, a blocking issue on
 *       a "current" plan, an issue without classification, a missing status): refused as unconfirmed.</li>
 * </ul>
 */
public final class ValidityPolicy {

    public static final String CHANGES_MESSAGE =
            "The plan is current with non-blocking catalog changes reported by Semantic.";

    public record Decision(Status status, List<ExecutionWarning> warnings) {
        public Decision {
            warnings = List.copyOf(warnings);
        }
    }

    public Decision decide(UUID planId, PlanValidity validity) {
        if (validity == null || validity.status() == null || validity.issues() == null
                || !Objects.equals(planId, validity.planId())) {
            throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "validity missing or for another plan");
        }
        for (Issue issue : validity.issues()) {
            if (issue == null || issue.blocking() == null || issue.code() == null) {
                throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "unclassified validity issue");
            }
        }
        boolean blocking = validity.issues().stream().anyMatch(Issue::blocking);
        return switch (validity.status()) {
            case STALE -> throw new ExecutionException(ErrorCode.PLAN_STALE, "plan is STALE");
            case CURRENT -> {
                if (!validity.issues().isEmpty()) {
                    throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "CURRENT plan with issues");
                }
                yield new Decision(Status.CURRENT, List.of());
            }
            case CURRENT_WITH_CHANGES -> {
                if (blocking || validity.issues().isEmpty()) {
                    throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED,
                            "CURRENT_WITH_CHANGES without exclusively non-blocking issues");
                }
                yield new Decision(Status.CURRENT_WITH_CHANGES, List.of(new ExecutionWarning(
                        ExecutionWarning.Code.PLAN_CURRENT_WITH_CHANGES, CHANGES_MESSAGE)));
            }
        };
    }
}
