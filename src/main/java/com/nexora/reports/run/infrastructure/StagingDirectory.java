package com.nexora.reports.run.infrastructure;

import com.nexora.reports.execution.application.port.StagingArea;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** A4-B9: the {@link StagingArea} port over {@link ArtifactStaging} (A4-B8, unchanged). */
public class StagingDirectory implements StagingArea {

    private final ArtifactStaging staging;

    public StagingDirectory(ArtifactStaging staging) {
        this.staging = Objects.requireNonNull(staging, "staging");
    }

    @Override
    public Staged create() throws IOException {
        ArtifactStaging.StagedFile file = staging.create();
        return new Staged() {
            @Override
            public Path path() {
                return file.path();
            }

            @Override
            public OutputStream openOutput() throws IOException {
                return file.openOutput();
            }

            @Override
            public void close() throws IOException {
                file.close();
            }
        };
    }

    /** Deletes leftovers of a crash older than {@code age}; returns how many. */
    public int sweep(Duration age) throws IOException {
        return staging.sweep(age);
    }

    public Path root() {
        return staging.root();
    }
}
