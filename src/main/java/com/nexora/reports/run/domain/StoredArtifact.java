package com.nexora.reports.run.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The metadata of a stored artifact: the fields of {@code nexora-report-artifact/1} and who it belongs to. */
public record StoredArtifact(UUID artifactId, UUID runId, UUID tenantId, UUID dataSourceId, String mediaType,
                             String excelContract, String sha256, long sizeBytes, List<String> sheets,
                             Instant createdAt, Instant expiresAt) {

    public StoredArtifact {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(excelContract, "excelContract");
        Objects.requireNonNull(sha256, "sha256");
        sheets = List.copyOf(sheets);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
