package com.nexora.reports.execution.application.port;

import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunScope;

import java.util.UUID;

/**
 * Hands over the artifact of a run to its owner, verified before it is handed over. It records that the store handed
 * it over ({@code ARTIFACT_RETRIEVED}), never that a client received it: that is B9's (adjustment 3).
 */
public interface ArtifactReader {

    Retrieval open(RunScope scope, UUID runId);
}
