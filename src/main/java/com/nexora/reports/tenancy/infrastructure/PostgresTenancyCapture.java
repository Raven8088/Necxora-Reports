package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.infrastructure.ConnectionPools;
import com.nexora.reports.tenancy.application.port.TenancyCapture;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Captures what a tenancy registration asserts from the database itself (A4-B5), with the source's read-only
 * credentials, in a read-only REPEATABLE READ transaction that is always rolled back. It reads exactly the facts
 * the execution probe compares later ({@link TenancyCatalog}).
 */
public class PostgresTenancyCapture implements TenancyCapture {

    private final ConnectionPools pools;

    public PostgresTenancyCapture(ConnectionPools pools) {
        this.pools = Objects.requireNonNull(pools, "pools");
    }

    @Override
    public Captured capture(DataSourceCredentials credentials) {
        try (Connection connection = pools.pool(credentials).getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                TenancyCatalog catalog = new TenancyCatalog(new ConnectionCatalogReader(connection));
                if (!catalog.canReadSystemIdentifier()) {
                    throw new TenancyException(TenancyException.Reason.CAPTURE_MISMATCH,
                            "the cluster system identifier cannot be read");
                }
                TenancyCatalog.Identity identity = catalog.identity();
                List<String> forbidden = catalog.roles().stream()
                        .filter(r -> r.superuser() || TenancyCatalog.FORBIDDEN_ROLES.contains(r.name()))
                        .map(TenancyCatalog.Role::name).toList();
                return new Captured(new DatabaseIdentity(credentials.host(), credentials.port(),
                        identity.databaseName(), identity.databaseOid(), identity.systemIdentifier(), identity.role()),
                        catalog.usableSchemas(), catalog.encrypted(), forbidden);
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot capture the tenancy of the source (SQLSTATE "
                    + e.getSQLState() + ")", e);
        }
    }
}
