package com.nexora.reports.result.domain;

import java.util.Objects;

/** A physical object of the source, by natural key ({@code column} is null for a table or view). */
public record SourceObject(String type, String schema, String name, String column) {

    public SourceObject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(name, "name");
    }
}
