package com.nexora.reports.config;

import com.nexora.reports.execution.application.port.RunMaintenance;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.run.infrastructure.ArtifactStaging;
import com.nexora.reports.run.infrastructure.StagingDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B9 (D-B9-7 to D-B9-9): the configuration of runs and storage has safe defaults, refuses values outside its
 * maxima (so Reports does not start with them), and the housekeeping works in bounded batches and survives failures.
 */
class ReportRunSettingsTest {

    @Test
    void runPropertiesHaveTheDocumentedDefaults() {
        ReportRunProperties p = new ReportRunProperties(null, null, null, null, null, null);

        assertThat(p.maxDuration()).isEqualTo(Duration.ofSeconds(120));
        assertThat(p.maxConcurrentRenders()).isEqualTo(4);
        assertThat(p.maxConcurrentDownloads()).isEqualTo(4);
        assertThat(p.housekeepingBatch()).isEqualTo(500);
        assertThat(p.stagingMaxAge()).isEqualTo(Duration.ofHours(1));
        assertThat(p.reportsVersion()).isEqualTo("unknown");
        assertThat(new ReportRunProperties(null, null, null, null, null, "@project.version@").reportsVersion())
                .isEqualTo("unknown");
    }

    @Test
    void runPropertiesOutsideTheirRangeStopTheStart() {
        assertThatThrownBy(() -> new ReportRunProperties(null, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportRunProperties(null, null, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportRunProperties(null, null, null, 1001, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportRunProperties(null, null, null, null, Duration.ofMinutes(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storageLimitsAreBoundAndRefusedAboveTheirMaxima() {
        Path dir = Path.of("storage");
        assertThat(new StorageProperties(dir, null, null, null).limits()).isEqualTo(StorageLimits.DEFAULTS);
        assertThat(new StorageProperties(dir, Duration.ofDays(1), 100L * 1024 * 1024, 10L * 1024 * 1024).limits()
                .retention()).isEqualTo(Duration.ofDays(1));
        assertThatThrownBy(() -> new StorageProperties(dir, Duration.ofDays(31), null, null).limits())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties(dir, null, 6L * 1024 * 1024 * 1024, null).limits())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties(dir, null, null, 21L * 1024 * 1024).limits())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StorageProperties(dir, null, null, null).staging()).isEqualTo(dir.resolve("staging"));
        assertThat(new StorageProperties(dir, null, null, null).poiFiles()).isEqualTo(dir.resolve("poifiles"));
    }

    private static final class CountingMaintenance implements RunMaintenance {
        final List<Integer> purges = new ArrayList<>();
        final List<Integer> closes = new ArrayList<>();
        int pendingPurge;
        int pendingClose;
        boolean failing;

        @Override
        public int purgeExpired(int batch) {
            if (failing) {
                throw new IllegalStateException("database down");
            }
            int n = Math.min(batch, pendingPurge);
            pendingPurge -= n;
            purges.add(n);
            return n;
        }

        @Override
        public int closeAbandoned(int batch) {
            if (failing) {
                throw new IllegalStateException("database down");
            }
            int n = Math.min(batch, pendingClose);
            pendingClose -= n;
            closes.add(n);
            return n;
        }
    }

    @Test
    void housekeepingRepeatsFullBatchesAndStopsAtAPartialOne() throws IOException {
        CountingMaintenance maintenance = new CountingMaintenance();
        maintenance.pendingClose = 1_250;
        maintenance.pendingPurge = 10;
        RunHousekeeping housekeeping = new RunHousekeeping(maintenance,
                new StagingDirectory(new ArtifactStaging(Files.createTempDirectory("b9-hk"))), 500,
                Duration.ofHours(1));

        assertThat(housekeeping.reconcile()).isEqualTo(1_250);
        assertThat(maintenance.closes).containsExactly(500, 500, 250);
        assertThat(housekeeping.purge()).isEqualTo(10);
        assertThat(maintenance.purges).containsExactly(10);
    }

    @Test
    void aBacklogLargerThanOneFiringIsLeftForTheNext() throws IOException {
        CountingMaintenance maintenance = new CountingMaintenance();
        maintenance.pendingClose = 1_000_000;
        RunHousekeeping housekeeping = new RunHousekeeping(maintenance,
                new StagingDirectory(new ArtifactStaging(Files.createTempDirectory("b9-hk"))), 10,
                Duration.ofHours(1));

        assertThat(housekeeping.reconcile()).isEqualTo(10 * RunHousekeeping.MAX_BATCHES);
    }

    @Test
    void aFailingStepIsLoggedAndRetriedLaterNeverThrown() throws IOException {
        CountingMaintenance maintenance = new CountingMaintenance();
        maintenance.failing = true;
        RunHousekeeping housekeeping = new RunHousekeeping(maintenance,
                new StagingDirectory(new ArtifactStaging(Files.createTempDirectory("b9-hk"))), 500,
                Duration.ofHours(1));

        assertThat(housekeeping.reconcile()).isEqualTo(0);
        assertThat(housekeeping.purge()).isEqualTo(0);
    }

    @Test
    void theSweepDeletesOnlyStagedFilesOlderThanTheirMaximumAge() throws IOException {
        Path root = Files.createTempDirectory("b9-sweep");
        ArtifactStaging staging = new ArtifactStaging(root);
        Path old = staging.create().path();
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Path recent = staging.create().path();
        Path foreign = Files.writeString(root.resolve("notes.txt"), "not a staged file");
        Files.setLastModifiedTime(foreign, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        RunHousekeeping housekeeping = new RunHousekeeping(new CountingMaintenance(), new StagingDirectory(staging),
                500, Duration.ofHours(1));

        assertThat(housekeeping.sweep()).isEqualTo(1);
        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(recent)).isTrue();
        assertThat(Files.exists(foreign)).isTrue();
    }
}
