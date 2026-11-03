package com.nexora.reports.result.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Limits of one presentation (A4 proposal v3, section 4 bis.5 b; D-B7-5).
 *
 * @param maxFileBytes   size of the artifact; initially 20 MiB. Enforced by a prior estimate and while writing
 * @param rowWindow      rows kept in memory per sheet by the streaming writer; initially 200
 * @param acquireTimeout how long to wait for a presentation slot
 */
public record RenderLimits(long maxFileBytes, int rowWindow, Duration acquireTimeout) {

    public static final long DEFAULT_MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final RenderLimits DEFAULTS = new RenderLimits(DEFAULT_MAX_FILE_BYTES, 200, Duration.ofSeconds(5));

    public RenderLimits {
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (maxFileBytes < 1 || rowWindow < 1 || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid render limits");
        }
    }
}
