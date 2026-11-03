package com.nexora.reports.result.domain;

import java.util.Objects;

/** A governed definition behind an output column (EX-14): its kind, key and revision, as carried by the plan. */
public record GovernedDefinition(String kind, String key, Integer revision) {

    public GovernedDefinition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
    }
}
