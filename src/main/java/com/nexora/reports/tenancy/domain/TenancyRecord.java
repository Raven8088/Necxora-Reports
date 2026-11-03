package com.nexora.reports.tenancy.domain;

import java.util.List;
import java.util.Objects;

/** A registration version with its decisions and its derived status. */
public record TenancyRecord(TenancyRegistration registration, List<TenancyDecision> decisions, TenancyStatus status) {

    public TenancyRecord {
        Objects.requireNonNull(registration, "registration");
        decisions = List.copyOf(decisions);
        Objects.requireNonNull(status, "status");
    }
}
