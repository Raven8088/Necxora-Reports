package com.nexora.reports.execution.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A4-B9: a controlled failure that concerns a run that exists (it was started and recorded), so the client gets its
 * id ({@code X-Run-Id}) together with the stable error code.
 */
public class RunException extends ExecutionException {

    private static final long serialVersionUID = 1L;

    private final UUID runId;

    public RunException(ErrorCode code, UUID runId, String internalReason) {
        super(code, internalReason);
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    public RunException(ErrorCode code, UUID runId, String internalReason, Throwable cause) {
        super(code, internalReason, cause);
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    public UUID runId() {
        return runId;
    }
}
