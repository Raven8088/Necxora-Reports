package com.nexora.reports.plan2.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The data source of a report plan as Nexora Semantic describes it: its id, engine, status and, when Semantic gives
 * it, its name (A4-B9, D-B9-2: the name becomes a known fact of the report header instead of an unavailable one).
 */
public record SemanticDataSource(UUID id, String engine, String status, Optional<String> name) {

    public static final String POSTGRESQL = "postgresql";
    public static final String ACTIVE = "ACTIVE";

    public SemanticDataSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(name, "name");
    }

    /** Only an active PostgreSQL source can be read by Reports. */
    public boolean executable() {
        return POSTGRESQL.equals(engine) && ACTIVE.equals(status);
    }
}
