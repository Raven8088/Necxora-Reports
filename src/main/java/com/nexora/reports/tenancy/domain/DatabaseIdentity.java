package com.nexora.reports.tenancy.domain;

import java.util.Objects;

/**
 * What identifies the PostgreSQL database a data source points to, as captured from the database itself when the
 * source is registered. {@code systemIdentifier} ({@code pg_control_system()}) identifies the <em>cluster</em>, not a
 * database: it is combined with the database name and OID. Together they detect a different server, a different
 * database or a re-created one; they are not an absolute guarantee against every restore (a physical copy of a
 * cluster keeps its system identifier and OIDs), which is why changes must be re-registered and re-approved.
 *
 * @param host             host of the connection, as configured
 * @param port             port of the connection, as configured
 * @param databaseName     {@code current_database()}
 * @param databaseOid      OID of that database in {@code pg_database}
 * @param systemIdentifier {@code pg_control_system().system_identifier}, as text
 * @param role             {@code current_user} of the read-only connection
 */
public record DatabaseIdentity(String host, int port, String databaseName, long databaseOid, String systemIdentifier,
                               String role) {

    public DatabaseIdentity {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(databaseName, "databaseName");
        Objects.requireNonNull(systemIdentifier, "systemIdentifier");
        Objects.requireNonNull(role, "role");
        if (port < 1 || port > 65535 || databaseOid < 1 || systemIdentifier.isBlank()) {
            throw new IllegalArgumentException("invalid database identity");
        }
    }
}
