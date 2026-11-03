package com.nexora.reports.run.infrastructure;

import com.nexora.reports.execution.application.port.ArtifactReader;
import com.nexora.reports.execution.application.port.RunReader;
import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ResultWarning;
import com.nexora.reports.run.domain.ArtifactState;
import com.nexora.reports.run.domain.FailureCode;
import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunOwner;
import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunState;
import com.nexora.reports.run.domain.RunStoreException;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.run.domain.StoredArtifact;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads runs and hands over artifacts to their owner (A4-B8). Every statement is filtered by tenant, source and owner
 * (D-A4-6, D-B8-7): a run outside the scope is indistinguishable from one that does not exist. The artifact is
 * verified (size and SHA-256) before it is handed over; a mismatch hands over nothing and records
 * {@code ARTIFACT_INTEGRITY_FAILED}. A successful hand-over records {@code ARTIFACT_RETRIEVED}: the store handed the
 * bytes to its caller, which says nothing about a client receiving them (B9 confirms deliveries).
 */
public class JdbcRunReader implements RunReader, ArtifactReader {

    private final DataSource dataSource;
    private final StorageLimits limits;

    public JdbcRunReader(DataSource dataSource, StorageLimits limits) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public Optional<RunView> find(RunScope scope, UUID runId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(runId, "runId");
        try (Connection c = dataSource.getConnection()) {
            RunView view;
            try (PreparedStatement ps = c.prepareStatement(RunSql.FIND_RUN)) {
                scoped(ps, scope, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    RunOwner owner = new RunOwner(rs.getString(5), RunOwner.Type.valueOf(rs.getString(6)));
                    boolean overdue = rs.getBoolean(9);
                    String kindText = rs.getString(10);
                    Optional<RunOutcome> outcome = Optional.empty();
                    Optional<Instant> finished = Optional.empty();
                    if (kindText != null) {
                        RunOutcomeKind kind = RunOutcomeKind.valueOf(kindText);
                        String failure = rs.getString(14);
                        outcome = Optional.of(new RunOutcome(kind, trim(rs.getString(11)), trim(rs.getString(12)),
                                rs.getString(13), failure == null ? null : FailureCode.of(failure), rs.getString(15),
                                datasets(c, scope.tenantId(), runId), warnings(c, scope.tenantId(), runId)));
                        finished = Optional.of(Jdbc.instant(rs.getObject(16, OffsetDateTime.class)));
                    }
                    Optional<ArtifactRow> artifact = artifact(c, scope, runId);
                    view = new RunView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), owner,
                            Jdbc.instant(rs.getObject(7, OffsetDateTime.class)),
                            Jdbc.instant(rs.getObject(8, OffsetDateTime.class)),
                            RunState.of(outcome.map(RunOutcome::kind), overdue), outcome, finished,
                            artifact.map(ArtifactRow::artifact), artifact.map(ArtifactRow::state));
                }
            }
            return Optional.of(view);
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "run not readable", e);
        }
    }

    @Override
    public Retrieval open(RunScope scope, UUID runId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(runId, "runId");
        try (Connection c = dataSource.getConnection()) {
            Optional<ArtifactRow> row = artifact(c, scope, runId);
            if (row.isEmpty()) {
                return Retrieval.NOT_FOUND;
            }
            StoredArtifact artifact = row.get().artifact();
            if (row.get().state() != ArtifactState.AVAILABLE) {
                return new Retrieval.Gone(artifact, row.get().state());
            }
            if (artifact.sizeBytes() > limits.maxFileBytes()) {
                return integrityFailed(c, artifact);
            }
            byte[] content = null;
            try (PreparedStatement ps = c.prepareStatement(RunSql.READ_CONTENT)) {
                ps.setObject(1, scope.tenantId());
                ps.setObject(2, artifact.artifactId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        content = rs.getBytes(1);
                    }
                }
            }
            if (content == null) {
                return new Retrieval.Gone(artifact, ArtifactState.PURGED);
            }
            MessageDigest digest = JdbcRunStore.sha256();
            if (content.length != artifact.sizeBytes()
                    || !HexFormat.of().formatHex(digest.digest(content)).equals(artifact.sha256())) {
                return integrityFailed(c, artifact);
            }
            JdbcRunStore.event(c, artifact.tenantId(), artifact.runId(), artifact.artifactId(), "ARTIFACT_RETRIEVED",
                    null, artifact.sha256(), artifact.sizeBytes());
            return new Retrieval.Available(artifact, content);
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "artifact not readable", e);
        }
    }

    private static Retrieval integrityFailed(Connection c, StoredArtifact artifact) throws SQLException {
        JdbcRunStore.event(c, artifact.tenantId(), artifact.runId(), artifact.artifactId(),
                "ARTIFACT_INTEGRITY_FAILED", null, artifact.sha256(), artifact.sizeBytes());
        return new Retrieval.IntegrityFailed(artifact);
    }

    private record ArtifactRow(StoredArtifact artifact, ArtifactState state) {
    }

    private static Optional<ArtifactRow> artifact(Connection c, RunScope scope, UUID runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RunSql.FIND_ARTIFACT)) {
            scoped(ps, scope, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String[] sheets = (String[]) rs.getArray(9).getArray();
                StoredArtifact artifact = new StoredArtifact(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        trim(rs.getString(7)), rs.getLong(8), Arrays.asList(sheets),
                        Jdbc.instant(rs.getObject(10, OffsetDateTime.class)),
                        Jdbc.instant(rs.getObject(11, OffsetDateTime.class)));
                return Optional.of(new ArtifactRow(artifact, ArtifactState.of(rs.getBoolean(13), rs.getBoolean(12))));
            }
        }
    }

    private static List<Methodology.DatasetExecution> datasets(Connection c, UUID tenantId, UUID runId)
            throws SQLException {
        List<Methodology.DatasetExecution> datasets = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(RunSql.FIND_DATASETS)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    datasets.add(new Methodology.DatasetExecution(rs.getString(1), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getInt(4), rs.getBoolean(5), rs.getInt(6), rs.getLong(7)));
                }
            }
        }
        return datasets;
    }

    private static List<ResultWarning> warnings(Connection c, UUID tenantId, UUID runId) throws SQLException {
        List<ResultWarning> warnings = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(RunSql.FIND_WARNINGS)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    warnings.add(new ResultWarning(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5)));
                }
            }
        }
        return warnings;
    }

    private static void scoped(PreparedStatement ps, RunScope scope, UUID runId) throws SQLException {
        ps.setObject(1, scope.tenantId());
        ps.setObject(2, scope.dataSourceId());
        ps.setString(3, scope.ownerSubject());
        ps.setObject(4, runId);
    }

    /** {@code char(64)} columns come back padded only if shorter; fingerprints are always 64, so this is defensive. */
    private static String trim(String s) {
        return s == null ? null : s.strip();
    }
}
