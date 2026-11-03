package com.nexora.reports.run.infrastructure;

import com.nexora.reports.execution.application.port.ArtifactDeliveries;
import com.nexora.reports.run.domain.RunStoreException;
import com.nexora.reports.run.domain.StoredArtifact;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A4-B9 (D-B9-4): records {@code ARTIFACT_DELIVERED} and {@code ARTIFACT_DELIVERY_FAILED} (migration V4) with the
 * same insert as every other event of the registry: tenant, run, artifact, digest and size, never content.
 */
public class JdbcArtifactDeliveries implements ArtifactDeliveries {

    static final String DELIVERED = "ARTIFACT_DELIVERED";
    static final String DELIVERY_FAILED = "ARTIFACT_DELIVERY_FAILED";
    private static final Pattern DETAIL = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");

    private final DataSource dataSource;

    public JdbcArtifactDeliveries(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void delivered(StoredArtifact artifact) {
        record(artifact, DELIVERED, null);
    }

    @Override
    public void deliveryFailed(StoredArtifact artifact, String detail) {
        if (detail == null || !DETAIL.matcher(detail).matches()) {
            throw new IllegalArgumentException("invalid delivery detail");
        }
        record(artifact, DELIVERY_FAILED, detail);
    }

    private void record(StoredArtifact artifact, String kind, String detail) {
        Objects.requireNonNull(artifact, "artifact");
        try (Connection c = dataSource.getConnection()) {
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            JdbcRunStore.event(c, artifact.tenantId(), artifact.runId(), artifact.artifactId(), kind, detail,
                    artifact.sha256(), artifact.sizeBytes());
        } catch (SQLException e) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "delivery not recorded", e);
        }
    }
}
