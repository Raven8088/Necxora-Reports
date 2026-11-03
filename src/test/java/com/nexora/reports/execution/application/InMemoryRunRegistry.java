package com.nexora.reports.execution.application;

import com.nexora.reports.execution.application.port.ArtifactDeliveries;
import com.nexora.reports.execution.application.port.ArtifactReader;
import com.nexora.reports.execution.application.port.RunReader;
import com.nexora.reports.execution.application.port.RunStore;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.run.domain.ArtifactState;
import com.nexora.reports.run.domain.FinishResult;
import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunRef;
import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunStart;
import com.nexora.reports.run.domain.RunState;
import com.nexora.reports.run.domain.RunStoreException;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StoredArtifact;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test double of the run registry of A4-B8 with its observable rules: one outcome per run, never an artifact for a
 * refused or failed run, quota and size refusals, bytes verified when handed over, reads scoped by tenant, source and
 * owner. The JDBC implementation is tested against PostgreSQL in A4-B8; this one lets the run service be tested step
 * by step.
 */
final class InMemoryRunRegistry implements RunStore, RunReader, ArtifactReader, ArtifactDeliveries {

    record Stored(RunStart start, Instant startedAt, RunOutcome outcome, Instant finishedAt, StoredArtifact artifact,
                  byte[] content) {
    }

    final Map<UUID, Stored> runs = new LinkedHashMap<>();
    final List<String> events = new ArrayList<>();
    boolean failBegin;
    boolean failFinish;
    boolean overQuota;
    boolean expired;
    boolean corrupt;
    boolean overdue;
    Instant now = Instant.parse("2026-11-01T16:42:03.123456Z");

    @Override
    public synchronized void begin(RunStart start) {
        if (failBegin) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "down", null);
        }
        if (runs.containsKey(start.runId())) {
            throw new RunStoreException(RunStoreException.Code.ALREADY_STARTED, "again", null);
        }
        runs.put(start.runId(), new Stored(start, now, null, null, null, null));
        events.add("RUN_STARTED");
    }

    @Override
    public synchronized FinishResult finish(RunRef run, RunOutcome outcome) {
        if (outcome.kind().hasArtifact()) {
            throw new IllegalArgumentException("a delivered outcome needs its artifact");
        }
        return record(run, outcome, null, null);
    }

    @Override
    public synchronized FinishResult finishWithArtifact(RunRef run, RunOutcome outcome, RenderedArtifact artifact,
                                                         Path content) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length != artifact.sizeBytes() || !sha256(bytes).equals(artifact.sha256())) {
            return record(run, outcome.withFailure(com.nexora.reports.run.domain.FailureCode.STORAGE_FAILED), null,
                    null);
        }
        if (overQuota) {
            return record(run, outcome.withRefusal(RunOutcome.ARTIFACT_QUOTA_EXCEEDED), null, null);
        }
        Instant created = now.plusSeconds(2);
        StoredArtifact stored = new StoredArtifact(UUID.randomUUID(), run.runId(), run.tenantId(), run.dataSourceId(),
                artifact.mediaType(), artifact.excelContract(), artifact.sha256(), artifact.sizeBytes(),
                artifact.sheets(), created, created.plus(Duration.ofDays(7)));
        events.add("ARTIFACT_STORED");
        return record(run, outcome, stored, bytes);
    }

    private FinishResult record(RunRef run, RunOutcome outcome, StoredArtifact artifact, byte[] content) {
        if (failFinish) {
            throw new RunStoreException(RunStoreException.Code.STORAGE_UNAVAILABLE, "down", null);
        }
        Stored stored = runs.get(run.runId());
        if (stored == null || !stored.start().tenantId().equals(run.tenantId())
                || !stored.start().dataSourceId().equals(run.dataSourceId())) {
            throw new RunStoreException(RunStoreException.Code.UNKNOWN_RUN, "unknown", null);
        }
        if (stored.outcome() != null) {
            return new FinishResult(stored.outcome().kind(), code(stored.outcome()), Optional.empty(), false);
        }
        runs.put(run.runId(), new Stored(stored.start(), stored.startedAt(), outcome, now.plusSeconds(2), artifact,
                content));
        events.add("RUN_FINISHED");
        return new FinishResult(outcome.kind(), code(outcome), Optional.ofNullable(artifact), true);
    }

    private static Optional<String> code(RunOutcome o) {
        if (o.refusalCode() != null) {
            return Optional.of(o.refusalCode());
        }
        return o.failureCode() == null ? Optional.empty() : Optional.of(o.failureCode().code());
    }

    private Optional<Stored> scoped(RunScope scope, UUID runId) {
        Stored s = runs.get(runId);
        if (s == null || !s.start().tenantId().equals(scope.tenantId())
                || !s.start().dataSourceId().equals(scope.dataSourceId())
                || !s.start().owner().subject().equals(scope.ownerSubject())) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    @Override
    public synchronized Optional<RunView> find(RunScope scope, UUID runId) {
        return scoped(scope, runId).map(s -> new RunView(runId, s.start().tenantId(), s.start().dataSourceId(),
                s.start().reportPlanId(), s.start().owner(), s.startedAt(), s.startedAt().plus(s.start().maxDuration()),
                RunState.of(Optional.ofNullable(s.outcome()).map(RunOutcome::kind), overdue),
                Optional.ofNullable(s.outcome()), Optional.ofNullable(s.finishedAt()),
                Optional.ofNullable(s.artifact()), Optional.ofNullable(s.artifact()).map(a -> state())));
    }

    private ArtifactState state() {
        return expired ? ArtifactState.EXPIRED : ArtifactState.AVAILABLE;
    }

    @Override
    public synchronized Retrieval open(RunScope scope, UUID runId) {
        Optional<Stored> s = scoped(scope, runId);
        if (s.isEmpty() || s.get().artifact() == null) {
            return Retrieval.NOT_FOUND;
        }
        StoredArtifact a = s.get().artifact();
        if (expired) {
            return new Retrieval.Gone(a, ArtifactState.EXPIRED);
        }
        if (corrupt) {
            events.add("ARTIFACT_INTEGRITY_FAILED");
            return new Retrieval.IntegrityFailed(a);
        }
        events.add("ARTIFACT_RETRIEVED");
        return new Retrieval.Available(a, s.get().content().clone());
    }

    @Override
    public synchronized void delivered(StoredArtifact artifact) {
        events.add("ARTIFACT_DELIVERED");
    }

    @Override
    public synchronized void deliveryFailed(StoredArtifact artifact, String detail) {
        events.add("ARTIFACT_DELIVERY_FAILED:" + detail);
    }

    RunOutcomeKind kind(UUID runId) {
        return runs.get(runId).outcome().kind();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
