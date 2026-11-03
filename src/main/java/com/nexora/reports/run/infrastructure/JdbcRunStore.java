package com.nexora.reports.run.infrastructure;

import com.nexora.reports.execution.application.port.RunStore;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.run.domain.FailureCode;
import com.nexora.reports.run.domain.FinishResult;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunRef;
import com.nexora.reports.run.domain.RunStart;
import com.nexora.reports.run.domain.RunStoreException;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.run.domain.StoredArtifact;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The run registry in Reports' database (migration V3; A4-B8).
 * <p>
 * <b>Start and outcome (the additional condition of «PROCEDE A4-B8»).</b> {@link #begin} commits the start on its
 * own, so a started run is always traceable. {@link #finishWithArtifact} then tries, in <b>one</b> transaction, to
 * store the outcome, the artifact, its content and their events, under a per-tenant lock that makes the quota check
 * exact. Any problem rolls that transaction back completely — no artifact, content, outcome or event remains — and the
 * outcome is recorded instead in a separate, minimal transaction:
 * <ul>
 *   <li>quota exceeded: REFUSED {@code artifact_quota_exceeded} (a refusal of the contract, not a failure);</li>
 *   <li>file over the size limit: REFUSED {@code artifact_too_large};</li>
 *   <li>file different from its description, or a database error while storing: FAILED {@code storage_failed}.</li>
 * </ul>
 * An outcome is inserted once (primary key on the run): if the reconciliation already closed the run as INTERRUPTED,
 * a late finish stores nothing and reports the existing outcome. If even the minimal transaction fails, the run keeps
 * its start without outcome and the reconciliation closes it after its deadline. Nothing is ever updated.
 */
public class JdbcRunStore implements RunStore {

    private static final int ARTIFACT_CHUNK = 64 * 1024;

    private final DataSource dataSource;
    private final StorageLimits limits;

    public JdbcRunStore(DataSource dataSource, StorageLimits limits) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public void begin(RunStart start) {
        Objects.requireNonNull(start, "start");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(RunSql.INSERT_RUN)) {
                    ps.setObject(1, start.runId());
                    ps.setObject(2, start.tenantId());
                    ps.setObject(3, start.dataSourceId());
                    ps.setObject(4, start.reportPlanId());
                    ps.setString(5, start.owner().subject());
                    ps.setString(6, start.owner().type().name());
                    ps.setLong(7, start.maxDuration().toSeconds());
                    ps.executeUpdate();
                }
                event(c, start.tenantId(), start.runId(), null, "RUN_STARTED", null, null, null);
                c.commit();
            } catch (SQLException e) {
                Jdbc.rollbackQuietly(c);
                if (Jdbc.is(e, Jdbc.UNIQUE_VIOLATION)) {
                    throw new RunStoreException(RunStoreException.Code.ALREADY_STARTED, "run already started", e);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "run start not recorded", e);
        }
    }

    @Override
    public FinishResult finish(RunRef run, RunOutcome outcome) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.kind() != RunOutcomeKind.REFUSED && outcome.kind() != RunOutcomeKind.FAILED) {
            throw new IllegalArgumentException("only REFUSED or FAILED end without artifact here");
        }
        return recordOutcome(run, outcome);
    }

    @Override
    public FinishResult finishWithArtifact(RunRef run, RunOutcome outcome, RenderedArtifact artifact, Path content) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(content, "content");
        if (!outcome.kind().hasArtifact()) {
            throw new IllegalArgumentException("only COMPLETED or PARTIAL have an artifact");
        }
        if (artifact.sizeBytes() > limits.maxFileBytes()) {
            return recordOutcome(run, outcome.withRefusal(RunOutcome.ARTIFACT_TOO_LARGE));
        }
        try {
            if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS) || Files.size(content) != artifact.sizeBytes()) {
                return recordOutcome(run, outcome.withFailure(FailureCode.STORAGE_FAILED));
            }
        } catch (IOException e) {
            return recordOutcome(run, outcome.withFailure(FailureCode.STORAGE_FAILED));
        }
        // Every fallback runs after the connection of the failed attempt is closed (a pool of one must not block).
        RunOutcome fallback = outcome.withFailure(FailureCode.STORAGE_FAILED);
        boolean closedBefore = false;
        Step step = Step.CONNECT;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                step = Step.QUOTA;
                lockTenant(c, run.tenantId());
                if (quotaUsed(c, run.tenantId()) + artifact.sizeBytes() > limits.tenantQuotaBytes()) {
                    c.rollback();
                    fallback = outcome.withRefusal(RunOutcome.ARTIFACT_QUOTA_EXCEEDED);
                } else {
                    step = Step.OUTCOME;
                    insertOutcome(c, run, outcome);
                    step = Step.ARTIFACT;
                    StoredArtifact stored = insertArtifact(c, run, artifact);
                    step = Step.CONTENT;
                    String sha256 = insertContent(c, run, stored.artifactId(), content, artifact.sizeBytes());
                    if (!sha256.equals(artifact.sha256())) {
                        c.rollback();
                    } else {
                        step = Step.EVENTS;
                        event(c, run.tenantId(), run.runId(), null, "RUN_FINISHED", outcome.kind().name(), null,
                                null);
                        event(c, run.tenantId(), run.runId(), stored.artifactId(), "ARTIFACT_STORED", null,
                                stored.sha256(), stored.sizeBytes());
                        step = Step.COMMIT;
                        c.commit();
                        return new FinishResult(outcome.kind(), Optional.empty(), Optional.of(stored), true);
                    }
                }
            } catch (SQLException e) {
                Jdbc.rollbackQuietly(c);
                if (step == Step.OUTCOME && Jdbc.is(e, Jdbc.UNIQUE_VIOLATION)) {
                    closedBefore = true;
                } else if (step == Step.OUTCOME && Jdbc.is(e, Jdbc.FOREIGN_KEY_VIOLATION)) {
                    throw new RunStoreException(RunStoreException.Code.UNKNOWN_RUN, "no such started run", e);
                }
            } catch (IOException | RuntimeException e) {
                Jdbc.rollbackQuietly(c);
            }
        } catch (SQLException e) {
            // no connection, or a broken one: nothing was committed
        }
        return closedBefore ? existing(run) : recordOutcome(run, fallback);
    }

    private enum Step { CONNECT, QUOTA, OUTCOME, ARTIFACT, CONTENT, EVENTS, COMMIT }

    /** The minimal transaction: the outcome and its event, never an artifact. */
    private FinishResult recordOutcome(RunRef run, RunOutcome outcome) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertOutcome(c, run, outcome);
                event(c, run.tenantId(), run.runId(), null, "RUN_FINISHED", outcome.kind().name(), null, null);
                c.commit();
                return new FinishResult(outcome.kind(), code(outcome.kind(), outcome.refusalCode(),
                        outcome.failureCode() == null ? null : outcome.failureCode().code()), Optional.empty(), true);
            } catch (SQLException e) {
                Jdbc.rollbackQuietly(c);
                if (Jdbc.is(e, Jdbc.UNIQUE_VIOLATION)) {
                    return existing(run);
                }
                if (Jdbc.is(e, Jdbc.FOREIGN_KEY_VIOLATION)) {
                    throw new RunStoreException(RunStoreException.Code.UNKNOWN_RUN, "no such started run", e);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "run outcome not recorded", e);
        }
    }

    /** The outcome another transaction recorded first (for example INTERRUPTED by the reconciliation). */
    private FinishResult existing(RunRef run) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(RunSql.OUTCOME_OF)) {
            ps.setObject(1, run.tenantId());
            ps.setObject(2, run.runId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "outcome vanished", null);
                }
                RunOutcomeKind kind = RunOutcomeKind.valueOf(rs.getString(1));
                return new FinishResult(kind, code(kind, rs.getString(2), rs.getString(3)), Optional.empty(), false);
            }
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "outcome not readable", e);
        }
    }

    private static Optional<String> code(RunOutcomeKind kind, String refusal, String failure) {
        return switch (kind) {
            case REFUSED -> Optional.ofNullable(refusal);
            case FAILED -> Optional.ofNullable(failure);
            default -> Optional.empty();
        };
    }

    private static void lockTenant(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RunSql.LOCK_TENANT)) {
            ps.setObject(1, tenantId);
            ps.execute();
        }
    }

    private static long quotaUsed(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RunSql.QUOTA_USED)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertOutcome(Connection c, RunRef run, RunOutcome o) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RunSql.INSERT_OUTCOME)) {
            ps.setObject(1, run.runId());
            ps.setObject(2, run.tenantId());
            ps.setObject(3, run.dataSourceId());
            ps.setString(4, o.kind().name());
            ps.setString(5, o.reportPlanFingerprint());
            ps.setString(6, o.specFingerprint());
            ps.setString(7, o.refusalCode());
            ps.setString(8, o.failureCode() == null ? null : o.failureCode().code());
            ps.setString(9, o.snapshot());
            ps.setString(10, RunJson.datasets(o.datasets()));
            ps.setString(11, RunJson.warnings(o.warnings()));
            ps.executeUpdate();
        }
    }

    private StoredArtifact insertArtifact(Connection c, RunRef run, RenderedArtifact a) throws SQLException {
        UUID artifactId = UUID.randomUUID();
        Array sheets = c.createArrayOf("text", a.sheets().toArray());
        try (PreparedStatement ps = c.prepareStatement(RunSql.INSERT_ARTIFACT)) {
            ps.setObject(1, artifactId);
            ps.setObject(2, run.runId());
            ps.setObject(3, run.tenantId());
            ps.setObject(4, run.dataSourceId());
            ps.setString(5, a.mediaType());
            ps.setString(6, a.excelContract());
            ps.setString(7, a.sha256());
            ps.setLong(8, a.sizeBytes());
            ps.setArray(9, sheets);
            ps.setLong(10, limits.retention().toSeconds());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Instant created = Jdbc.instant(rs.getObject(1, java.time.OffsetDateTime.class));
                Instant expires = Jdbc.instant(rs.getObject(2, java.time.OffsetDateTime.class));
                return new StoredArtifact(artifactId, run.runId(), run.tenantId(), run.dataSourceId(), a.mediaType(),
                        a.excelContract(), a.sha256(), a.sizeBytes(), a.sheets(), created, expires);
            }
        } finally {
            sheets.free();
        }
    }

    /** Streams the file into the content row, digesting it on the way; returns the SHA-256 of what was sent. */
    private static String insertContent(Connection c, RunRef run, UUID artifactId, Path file, long size)
            throws SQLException, IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest);
             PreparedStatement ps = c.prepareStatement(RunSql.INSERT_CONTENT)) {
            ps.setObject(1, artifactId);
            ps.setObject(2, run.tenantId());
            ps.setBinaryStream(3, new java.io.BufferedInputStream(in, ARTIFACT_CHUNK), size);
            ps.executeUpdate();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void event(Connection c, UUID tenantId, UUID runId, UUID artifactId, String kind, String detail,
                      String sha256, Long size) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RunSql.INSERT_EVENT)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, runId);
            ps.setObject(3, artifactId);
            ps.setString(4, kind);
            ps.setString(5, detail);
            ps.setString(6, sha256);
            if (size == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, size);
            }
            ps.executeUpdate();
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
