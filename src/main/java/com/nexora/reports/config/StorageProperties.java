package com.nexora.reports.config;

import com.nexora.reports.run.domain.StorageLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * A4-B9 (D-B9-8): Reports' own storage ({@code nexora.reports.storage.*}).
 * <ul>
 *   <li>{@code directory}: holds {@code staging} (artifacts between rendering and storing, A4-B8) and
 *       {@code poifiles} (Apache POI's temporary files, B7-O3); it must be private to Reports;</li>
 *   <li>{@code retention}, {@code tenant-quota-bytes}, {@code max-file-bytes}: {@link StorageLimits}; a value outside
 *       its documented maxima stops Reports from starting.</li>
 * </ul>
 */
@ConfigurationProperties("nexora.reports.storage")
public record StorageProperties(Path directory, Duration retention, Long tenantQuotaBytes, Long maxFileBytes) {

    public static final String STAGING = "staging";
    public static final String POI_FILES = "poifiles";

    public StorageProperties {
        if (directory == null) {
            throw new IllegalArgumentException("nexora.reports.storage.directory is required");
        }
    }

    public StorageLimits limits() {
        StorageLimits d = StorageLimits.DEFAULTS;
        return new StorageLimits(retention == null ? d.retention() : retention,
                tenantQuotaBytes == null ? d.tenantQuotaBytes() : tenantQuotaBytes,
                maxFileBytes == null ? d.maxFileBytes() : maxFileBytes);
    }

    public Path staging() {
        return directory.resolve(STAGING);
    }

    public Path poiFiles() {
        return directory.resolve(POI_FILES);
    }
}
