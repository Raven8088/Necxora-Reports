package com.nexora.reports.run.domain;

import com.nexora.reports.result.domain.RenderLimits;

import java.time.Duration;
import java.util.Objects;

/**
 * Storage limits (D-A4-3, P-2, D-B8-6), configurable within documented maxima; a value outside them is refused here,
 * so a configuration above the maxima stops Reports from starting (B9 binds them).
 * <ul>
 *   <li>retention of an artifact: 7 days by default, between 1 hour and 30 days;</li>
 *   <li>quota of current (not expired) artifacts per tenant: 500 MiB, at most 5 GiB, never below one file;</li>
 *   <li>file size: 20 MiB, the limit of A4-B7, which can be lowered but never raised.</li>
 * </ul>
 */
public record StorageLimits(Duration retention, long tenantQuotaBytes, long maxFileBytes) {

    public static final Duration MIN_RETENTION = Duration.ofHours(1);
    public static final Duration MAX_RETENTION = Duration.ofDays(30);
    public static final long MAX_TENANT_QUOTA_BYTES = 5L * 1024 * 1024 * 1024;
    public static final long MAX_FILE_BYTES = RenderLimits.DEFAULT_MAX_FILE_BYTES;
    public static final StorageLimits DEFAULTS = new StorageLimits(Duration.ofDays(7), 500L * 1024 * 1024,
            MAX_FILE_BYTES);

    public StorageLimits {
        Objects.requireNonNull(retention, "retention");
        if (retention.compareTo(MIN_RETENTION) < 0 || retention.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException("retention out of range");
        }
        if (maxFileBytes < 1 || maxFileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("maxFileBytes out of range");
        }
        if (tenantQuotaBytes < maxFileBytes || tenantQuotaBytes > MAX_TENANT_QUOTA_BYTES) {
            throw new IllegalArgumentException("tenantQuotaBytes out of range");
        }
    }
}
