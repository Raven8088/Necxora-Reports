package com.nexora.reports.credentials.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * B10-O3 (D-O3-2): the destination of a configured data source and how Reports connects to it, <b>without its
 * secret</b>. It carries no password, nor anything derived from one (no hash, length or reference), so it can be
 * looked up before a run starts without resolving credentials.
 * <p>
 * Equality is the identity of the destination: two targets are the same only if every component coincides, compared
 * exactly (a host written with other letter case, another CA certificate path or another pool size is another
 * target). The password is deliberately not part of it: a change of password alone is not a change of destination
 * (see the observation B10-O10 about pools after a rotation).
 *
 * @param sslMode     PostgreSQL JDBC {@code sslmode}
 * @param sslRootCert path of the CA certificate that {@code verify-full} trusts, or {@code null}
 */
public record SourceTarget(UUID tenantId, UUID dataSourceId, String host, int port, String database, String username,
                           String sslMode, int maxConcurrent, String sslRootCert) {

    public SourceTarget {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(sslMode, "sslMode");
        if (port < 1 || port > 65535 || maxConcurrent < 1) {
            throw new IllegalArgumentException("invalid port or concurrency");
        }
    }
}
