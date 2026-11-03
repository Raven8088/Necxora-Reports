package com.nexora.reports.plan2.domain;

import java.util.Objects;

/**
 * One reason why a report plan cannot be executed. {@code rule} names the contract rule or the A4 check that failed
 * ({@code SCHEMA}, {@code SPEC-R1}…{@code SPEC-R6}, {@code PLAN-R1}…{@code PLAN-R5}, {@code RPLAN-R1}, {@code BASIC-A},
 * {@code IDENTITY}, {@code FINGERPRINT}, {@code BUDGET}, {@code UNREADABLE}); {@code path} locates it by document,
 * field names and indexes only. Values from the documents are never copied into a violation, so violations can be
 * logged and audited without leaking questions, filter values or data.
 */
public record PlanV2Violation(String rule, Kind kind, String path) {

    /** Coarse category, mapped to the closed refusal reasons of {@code nexora-report-run/1} by the run service. */
    public enum Kind {
        /** The document does not satisfy its contract (structure or referential rules). */
        CONTRACT_INVALID,
        /** Valid for the contract, but outside what Reports executes in profile {@code basic-a}. */
        PLAN_NOT_SUPPORTED,
        /** Tenant, data source, datasets or plans do not correspond across the documents or with the request. */
        IDENTITY_MISMATCH,
        /** A recomputed fingerprint differs from the declared one. */
        FINGERPRINT_MISMATCH,
        /** A budget or limit exceeds what the report plan or Reports allows. */
        BUDGET_EXCEEDED
    }

    public PlanV2Violation {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
    }
}
