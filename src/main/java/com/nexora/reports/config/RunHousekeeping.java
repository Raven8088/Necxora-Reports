package com.nexora.reports.config;

import com.nexora.reports.execution.application.port.RunMaintenance;
import com.nexora.reports.run.infrastructure.StagingDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * A4-B9 (D-B9-9): the scheduled housekeeping of the run registry (A4-B8), each step in batches and repeated while a
 * batch comes back full, up to a bound per firing:
 * <ul>
 *   <li>reconciliation ({@code nexora.reports.runs.reconcile-interval}, every minute): runs past their deadline
 *       without outcome become INTERRUPTED, never twice;</li>
 *   <li>purge ({@code purge-interval}, every 15 minutes): the content of expired artifacts is deleted;</li>
 *   <li>sweep ({@code sweep-interval}, every hour): staged files older than {@code staging-max-age} are deleted.</li>
 * </ul>
 * A failure is logged and retried at the next firing; it never stops the schedule.
 */
public class RunHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(RunHousekeeping.class);
    /** Batches per firing at most, so one firing cannot hold the scheduler indefinitely. */
    static final int MAX_BATCHES = 20;

    private final RunMaintenance maintenance;
    private final StagingDirectory staging;
    private final int batch;
    private final Duration stagingMaxAge;

    public RunHousekeeping(RunMaintenance maintenance, StagingDirectory staging, int batch, Duration stagingMaxAge) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.staging = Objects.requireNonNull(staging, "staging");
        this.batch = batch;
        this.stagingMaxAge = Objects.requireNonNull(stagingMaxAge, "stagingMaxAge");
    }

    @Scheduled(fixedDelayString = "${nexora.reports.runs.reconcile-interval:PT1M}",
            initialDelayString = "${nexora.reports.runs.reconcile-interval:PT1M}")
    public void scheduledReconcile() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${nexora.reports.runs.purge-interval:PT15M}",
            initialDelayString = "${nexora.reports.runs.purge-interval:PT15M}")
    public void scheduledPurge() {
        purge();
    }

    @Scheduled(fixedDelayString = "${nexora.reports.runs.sweep-interval:PT1H}",
            initialDelayString = "${nexora.reports.runs.sweep-interval:PT1H}")
    public void scheduledSweep() {
        sweep();
    }

    /** One reconciliation now; returns how many runs were closed. */
    public int reconcile() {
        try {
            int closed = repeat(() -> maintenance.closeAbandoned(batch));
            if (closed > 0) {
                log.info("{} runs past their deadline recorded as INTERRUPTED", closed);
            }
            return closed;
        } catch (RuntimeException e) {
            log.warn("reconciliation of runs failed; retried at the next firing", e);
            return 0;
        }
    }

    /** One purge now; returns how many contents were deleted. */
    public int purge() {
        try {
            int purged = repeat(() -> maintenance.purgeExpired(batch));
            if (purged > 0) {
                log.info("content of {} expired artifacts purged", purged);
            }
            return purged;
        } catch (RuntimeException e) {
            log.warn("purge of expired artifacts failed; retried at the next firing", e);
            return 0;
        }
    }

    /** One sweep now; returns how many staged files were deleted. */
    public int sweep() {
        try {
            int deleted = staging.sweep(stagingMaxAge);
            if (deleted > 0) {
                log.info("{} leftover staged files deleted", deleted);
            }
            return deleted;
        } catch (IOException | RuntimeException e) {
            log.warn("sweep of the staging directory failed; retried at the next firing", e);
            return 0;
        }
    }

    private int repeat(java.util.function.IntSupplier step) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int done = step.getAsInt();
            total += done;
            if (done < batch) {
                break;
            }
        }
        return total;
    }
}
