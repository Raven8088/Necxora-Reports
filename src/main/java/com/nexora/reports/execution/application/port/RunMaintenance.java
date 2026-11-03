package com.nexora.reports.execution.application.port;

/**
 * Housekeeping of the run registry (A4-B8); B9 schedules it. Both act across tenants and return no content.
 */
public interface RunMaintenance {

    /** Deletes the content of up to {@code batch} expired artifacts and records {@code ARTIFACT_PURGED}; returns how many. */
    int purgeExpired(int batch);

    /**
     * Records INTERRUPTED for up to {@code batch} runs without outcome past their deadline, never twice for the same
     * run; returns how many.
     */
    int closeAbandoned(int batch);
}
