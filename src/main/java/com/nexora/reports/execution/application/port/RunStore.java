package com.nexora.reports.execution.application.port;

import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.run.domain.FinishResult;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunRef;
import com.nexora.reports.run.domain.RunStart;

import java.nio.file.Path;

/**
 * Records runs and their artifacts in Reports' database (A4-B8).
 * <p>
 * A run is recorded in two steps. {@link #begin} commits the start, before any data is read, so that every started
 * run stays traceable. Then exactly one outcome: {@link #finishWithArtifact} for COMPLETED and PARTIAL stores the
 * outcome, the artifact, its content and their events in <b>one</b> transaction; if it cannot (quota, size, integrity
 * or database error) that transaction is rolled back entirely, so nothing partial remains, and the outcome is recorded
 * in a separate, minimal transaction as REFUSED ({@code artifact_quota_exceeded}, {@code artifact_too_large}) or
 * FAILED ({@code storage_failed}). {@link #finish} records REFUSED or FAILED without artifact. A run that already has
 * an outcome (the reconciliation may have closed it as INTERRUPTED) is never given a second one.
 */
public interface RunStore {

    void begin(RunStart start);

    FinishResult finish(RunRef run, RunOutcome outcome);

    /**
     * @param content the file written by the renderer; it is read, never moved or deleted here (the caller owns it,
     *                see {@code ArtifactStaging})
     */
    FinishResult finishWithArtifact(RunRef run, RunOutcome outcome, RenderedArtifact artifact, Path content);
}
