package com.nexora.reports.plan.domain;

import java.util.UUID;

/**
 * The part of a Semantic data source ({@code GET /api/v1/data-sources/{id}}) that Reports checks before executing:
 * it must be the requested source, use the PostgreSQL engine and be ACTIVE. It never carries connection details.
 */
public record DataSourceDescriptor(UUID id, String engine, String status) {

    public static final String POSTGRESQL = "postgresql";
    public static final String ACTIVE = "ACTIVE";
}
