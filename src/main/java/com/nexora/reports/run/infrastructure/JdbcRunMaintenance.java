package com.nexora.reports.run.infrastructure;

import com.nexora.reports.execution.application.port.RunMaintenance;
import com.nexora.reports.run.domain.RunStoreException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Housekeeping of the run registry (A4-B8; B9 schedules it). Both statements act across tenants on purpose and are
 * the only ones that do ({@link RunSql#CROSS_TENANT}); they return ids and digests, never content.
 * <ul>
 *   <li>{@link #purgeExpired}: deletes the content of expired artifacts (the database refuses any other) and records
 *       {@code ARTIFACT_PURGED} in the same transaction; metadata stays, so the artifact reads as PURGED;</li>
 *   <li>{@link #closeAbandoned}: records INTERRUPTED for runs past their deadline without outcome, with
 *       {@code ON CONFLICT DO NOTHING}: a run finished meanwhile keeps its own outcome, and a late finish after this
 *       finds the run closed and stores nothing.</li>
 * </ul>
 */
public class JdbcRunMaintenance implements RunMaintenance {

    public static final int MAX_BATCH = 1000;

    private final DataSource dataSource;

    public JdbcRunMaintenance(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public int purgeExpired(int batch) {
        return run(RunSql.PURGE_EXPIRED, batch, true);
    }

    @Override
    public int closeAbandoned(int batch) {
        return run(RunSql.CLOSE_ABANDONED, batch, false);
    }

    private record Row(UUID id, UUID runId, UUID tenantId, String sha256, Long size) {
    }

    private int run(String sql, int batch, boolean purge) {
        if (batch < 1 || batch > MAX_BATCH) {
            throw new IllegalArgumentException("batch out of range");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<Row> rows = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, batch);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(purge
                                    ? new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                            rs.getObject(3, UUID.class), rs.getString(4), rs.getLong(5))
                                    : new Row(null, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), null,
                                            null));
                        }
                    }
                }
                for (Row r : rows) {
                    if (purge) {
                        JdbcRunStore.event(c, r.tenantId(), r.runId(), r.id(), "ARTIFACT_PURGED", null, r.sha256(),
                                r.size());
                    } else {
                        JdbcRunStore.event(c, r.tenantId(), r.runId(), null, "RUN_INTERRUPTED", null, null, null);
                        JdbcRunStore.event(c, r.tenantId(), r.runId(), null, "RUN_FINISHED", "INTERRUPTED", null,
                                null);
                    }
                }
                c.commit();
                return rows.size();
            } catch (SQLException e) {
                Jdbc.rollbackQuietly(c);
                throw e;
            }
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "housekeeping failed", e);
        }
    }
}
