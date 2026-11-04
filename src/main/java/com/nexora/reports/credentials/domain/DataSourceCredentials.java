package com.nexora.reports.credentials.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Read-only connection to one enterprise data source of one tenant. Resolved server-side from secure configuration
 * by {@code (tenantId, dataSourceId)}; clients never send any of these values. The password is never printed.
 *
 * @param sslMode             PostgreSQL JDBC {@code sslmode} (for example {@code require} or {@code verify-full})
 * @param maxConcurrent       maximum simultaneous executions against this source (pool size)
 * @param sslRootCert         A4-B5: optional path of the CA certificate that {@code verify-full} trusts; {@code null}
 *                            keeps the driver's default location (R1 behaviour unchanged)
 * @param transportException  EX-TLS-1: the temporary authorization to connect without TLS to exactly this source and
 *                            destination ({@link TransportPolicy}), or {@code null} (always in normal builds)
 */
public record DataSourceCredentials(UUID tenantId, UUID dataSourceId, String host, int port, String database,
                                    String username, String password, String sslMode, int maxConcurrent,
                                    String sslRootCert, TransportException transportException) {

    public DataSourceCredentials {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(sslMode, "sslMode");
        if (port < 1 || port > 65535 || maxConcurrent < 1) {
            throw new IllegalArgumentException("invalid port or concurrency");
        }
    }

    /** B10-O3: the destination of these credentials without their secret (what {@code SourceAvailability} gives). */
    public SourceTarget target() {
        return new SourceTarget(tenantId, dataSourceId, host, port, database, username, sslMode, maxConcurrent,
                sslRootCert);
    }

    /** The A4-B5 form, without a transport exception. */
    public DataSourceCredentials(UUID tenantId, UUID dataSourceId, String host, int port, String database,
                                 String username, String password, String sslMode, int maxConcurrent,
                                 String sslRootCert) {
        this(tenantId, dataSourceId, host, port, database, username, password, sslMode, maxConcurrent, sslRootCert,
                null);
    }

    /** The R1 form, without a CA certificate path. */
    public DataSourceCredentials(UUID tenantId, UUID dataSourceId, String host, int port, String database,
                                 String username, String password, String sslMode, int maxConcurrent) {
        this(tenantId, dataSourceId, host, port, database, username, password, sslMode, maxConcurrent, null, null);
    }

    @Override
    public String toString() {
        return "DataSourceCredentials[tenantId=" + tenantId + ", dataSourceId=" + dataSourceId + ", password=***]";
    }
}
