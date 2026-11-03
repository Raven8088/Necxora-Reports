package com.nexora.reports.execution.application.port;

import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunView;

import java.util.Optional;
import java.util.UUID;

/** Reads a run for its owner (D-A4-6): empty for any run outside the scope, exactly as for one that does not exist. */
public interface RunReader {

    Optional<RunView> find(RunScope scope, UUID runId);
}
