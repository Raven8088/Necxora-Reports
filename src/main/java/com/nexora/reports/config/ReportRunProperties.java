package com.nexora.reports.config;

import com.nexora.reports.execution.application.ReportRunService;
import com.nexora.reports.render.xlsx.XlsxRenderer;
import com.nexora.reports.run.infrastructure.JdbcRunMaintenance;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A4-B9 (D-B9-7, D-B9-9): limits and housekeeping of report runs ({@code nexora.reports.runs.*}). Values outside their
 * range stop Reports from starting.
 *
 * @param maxDuration            deadline of a run (default 120 s; between 1 s and 24 h)
 * @param maxConcurrentRenders   simultaneous presentations (default 4)
 * @param maxConcurrentDownloads simultaneous artifact downloads (default 4)
 * @param housekeepingBatch      rows handled per housekeeping step (default 500, at most 1000)
 * @param stagingMaxAge          age after which a staged file is a leftover of a crash (default 1 h)
 * @param reportsVersion         the version stated in every report header (the build's)
 */
@ConfigurationProperties("nexora.reports.runs")
public record ReportRunProperties(Duration maxDuration, Integer maxConcurrentRenders, Integer maxConcurrentDownloads,
                                  Integer housekeepingBatch, Duration stagingMaxAge, String reportsVersion) {

    public static final int DEFAULT_BATCH = 500;

    public ReportRunProperties {
        maxDuration = maxDuration == null ? ReportRunService.Settings.DEFAULT_MAX_DURATION : maxDuration;
        maxConcurrentRenders = maxConcurrentRenders == null ? XlsxRenderer.DEFAULT_MAX_CONCURRENT
                : maxConcurrentRenders;
        maxConcurrentDownloads = maxConcurrentDownloads == null
                ? ReportRunService.Settings.DEFAULT_MAX_CONCURRENT_DOWNLOADS : maxConcurrentDownloads;
        housekeepingBatch = housekeepingBatch == null ? DEFAULT_BATCH : housekeepingBatch;
        stagingMaxAge = stagingMaxAge == null ? Duration.ofHours(1) : stagingMaxAge;
        reportsVersion = reportsVersion == null || reportsVersion.isBlank() || reportsVersion.startsWith("@")
                ? "unknown" : reportsVersion;
        if (maxConcurrentRenders < 1 || maxConcurrentRenders > 64) {
            throw new IllegalArgumentException("nexora.reports.runs.max-concurrent-renders must be 1..64");
        }
        if (maxConcurrentDownloads < 1 || maxConcurrentDownloads > 64) {
            throw new IllegalArgumentException("nexora.reports.runs.max-concurrent-downloads must be 1..64");
        }
        if (housekeepingBatch < 1 || housekeepingBatch > JdbcRunMaintenance.MAX_BATCH) {
            throw new IllegalArgumentException("nexora.reports.runs.housekeeping-batch must be 1.."
                    + JdbcRunMaintenance.MAX_BATCH);
        }
        if (stagingMaxAge.compareTo(Duration.ofMinutes(10)) < 0) {
            throw new IllegalArgumentException("nexora.reports.runs.staging-max-age must be at least 10 minutes");
        }
    }
}
