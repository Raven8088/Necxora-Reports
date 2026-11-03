package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One small HikariCP pool per (tenant, data source), created lazily. Connections are built from discrete
 * properties of {@link PGSimpleDataSource} (never by concatenating a JDBC URL), so no value can add driver options
 * such as socket factories. Every session starts read-only, in UTC, with server-side timeouts.
 */
public class ConnectionPools implements AutoCloseable {

    private record Key(UUID tenantId, UUID dataSourceId) {
    }

    private final Map<Key, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final ExecutionLimits limits;

    public ConnectionPools(ExecutionLimits limits) {
        this.limits = limits;
    }

    public HikariDataSource pool(DataSourceCredentials credentials) {
        return pools.computeIfAbsent(new Key(credentials.tenantId(), credentials.dataSourceId()),
                key -> create(credentials));
    }

    private HikariDataSource create(DataSourceCredentials c) {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setServerNames(new String[]{c.host()});
        pg.setPortNumbers(new int[]{c.port()});
        pg.setDatabaseName(c.database());
        pg.setUser(c.username());
        pg.setPassword(c.password());
        pg.setSslmode(c.sslMode());
        if (c.sslRootCert() != null) {
            pg.setSslRootCert(c.sslRootCert());
        }
        pg.setApplicationName("nexora-reports");
        pg.setReadOnly(true);
        pg.setReadOnlyMode("always");
        // Session defaults enforced by the server for every transaction of this connection.
        pg.setOptions("-c default_transaction_read_only=on"
                + " -c statement_timeout=" + limits.statementTimeout().toMillis()
                + " -c lock_timeout=" + limits.lockTimeout().toMillis()
                + " -c idle_in_transaction_session_timeout=" + limits.maxDuration().plusSeconds(5).toMillis()
                + " -c TimeZone=UTC");
        pg.setConnectTimeout(seconds(limits.acquireTimeout().plusSeconds(3)));
        pg.setLoginTimeout(seconds(limits.acquireTimeout().plusSeconds(3)));
        pg.setSocketTimeout(seconds(limits.maxDuration().plusSeconds(10)));
        pg.setTcpKeepAlive(true);

        HikariConfig config = new HikariConfig();
        config.setDataSource(pg);
        config.setPoolName("reports-" + c.dataSourceId());
        config.setMaximumPoolSize(c.maxConcurrent());
        config.setMinimumIdle(0);
        config.setAutoCommit(false);
        config.setReadOnly(true);
        config.setConnectionTimeout(Math.max(limits.acquireTimeout().toMillis(), 250) + 3000);
        config.setIdleTimeout(Duration.ofMinutes(5).toMillis());
        config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static int seconds(Duration duration) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, duration.toSeconds()));
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
