package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.tenancy.application.port.TenancyRegistry;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRegistration;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenancy registry in Reports' own database (migration V2): {@code tenancy_registration} holds immutable versions
 * and {@code tenancy_decision} the approvals and revocations. Both are append-only (triggers) and every read is
 * scoped by tenant. The database also refuses {@code SHARED_ROWS} and an approval by the registrant.
 */
public class JdbcTenancyRegistry implements TenancyRegistry {

    private static final String REGISTRATION_COLUMNS = "id, tenant_id, data_source_id, version, model, host, port,"
            + " database_name, database_oid, system_identifier, role_name, schemas, registered_by, registered_at,"
            + " fingerprint";

    private final DataSource dataSource;

    public JdbcTenancyRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<TenancyRegistration> registrations(UUID tenantId, UUID dataSourceId) {
        return query("SELECT " + REGISTRATION_COLUMNS + " FROM tenancy_registration"
                + " WHERE tenant_id = ? AND data_source_id = ? ORDER BY version", tenantId, dataSourceId);
    }

    @Override
    public Optional<TenancyRegistration> registration(UUID tenantId, UUID registrationId) {
        return query("SELECT " + REGISTRATION_COLUMNS + " FROM tenancy_registration WHERE tenant_id = ? AND id = ?",
                tenantId, registrationId).stream().findFirst();
    }

    @Override
    public List<TenancyDecision> decisions(UUID tenantId, UUID dataSourceId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT d.id, d.registration_id, d.kind, d.decided_by, d.decided_at
                  FROM tenancy_decision d JOIN tenancy_registration r ON r.id = d.registration_id
                 WHERE r.tenant_id = ? AND r.data_source_id = ? ORDER BY d.decided_at, d.id""")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, dataSourceId);
            List<TenancyDecision> decisions = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    decisions.add(new TenancyDecision(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            TenancyDecision.Kind.valueOf(rs.getString(3)), rs.getString(4),
                            rs.getTimestamp(5).toInstant()));
                }
            }
            return decisions;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void insert(TenancyRegistration r) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenancy_registration (" + REGISTRATION_COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            DatabaseIdentity identity = r.identity();
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.dataSourceId());
            ps.setInt(4, r.version());
            ps.setString(5, r.model().name());
            ps.setString(6, identity.host());
            ps.setInt(7, identity.port());
            ps.setString(8, identity.databaseName());
            ps.setLong(9, identity.databaseOid());
            ps.setString(10, identity.systemIdentifier());
            ps.setString(11, identity.role());
            ps.setArray(12, c.createArrayOf("text", r.schemas().toArray()));
            ps.setString(13, r.registeredBy());
            ps.setTimestamp(14, Timestamp.from(r.registeredAt()));
            ps.setString(15, r.fingerprint());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public void insert(TenancyDecision d) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenancy_decision (id, registration_id, kind, decided_by, decided_at)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, d.id());
            ps.setObject(2, d.registrationId());
            ps.setString(3, d.kind().name());
            ps.setString(4, d.decidedBy());
            ps.setTimestamp(5, Timestamp.from(d.decidedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private List<TenancyRegistration> query(String sql, UUID first, UUID second) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, first);
            ps.setObject(2, second);
            List<TenancyRegistration> registrations = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Array schemas = rs.getArray(12);
                    registrations.add(new TenancyRegistration(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
                            TenancyModel.valueOf(rs.getString(5)), new DatabaseIdentity(rs.getString(6),
                            rs.getInt(7), rs.getString(8), rs.getLong(9), rs.getString(10), rs.getString(11)),
                            Arrays.stream((Object[]) schemas.getArray()).map(String.class::cast).toList(),
                            rs.getString(13), rs.getTimestamp(14).toInstant(), rs.getString(15)));
                }
            }
            return registrations;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static IllegalStateException failure(SQLException e) {
        return new IllegalStateException("tenancy registry unavailable (SQLSTATE " + e.getSQLState() + ")", e);
    }
}
