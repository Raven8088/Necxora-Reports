package com.nexora.reports.plan2.domain;

import java.util.Objects;

/** The compiled schemas of the three contracts Reports reads in A4. */
public record ContractSchemas(ContractSchema reportSpec, ContractSchema logicalPlan, ContractSchema reportPlan) {

    public ContractSchemas {
        Objects.requireNonNull(reportSpec, "reportSpec");
        Objects.requireNonNull(logicalPlan, "logicalPlan");
        Objects.requireNonNull(reportPlan, "reportPlan");
    }
}
