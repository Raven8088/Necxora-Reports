package com.nexora.reports.run.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B8 (D-B8-4): the staging directory: random names inside the root only, deletion on close, crash sweeping. */
class ArtifactStagingTest {

    @Test
    void stagedFilesHaveRandomNamesDirectlyInsideTheRoot() throws IOException {
        Path dir = Files.createTempDirectory("b8-staging");
        try {
            ArtifactStaging staging = new ArtifactStaging(dir.resolve("staging"));
            try (ArtifactStaging.StagedFile a = staging.create(); ArtifactStaging.StagedFile b = staging.create()) {
                assertThat(a.path().getParent()).isEqualTo(staging.root());
                assertThat(ArtifactStaging.NAME.matcher(a.path().getFileName().toString()).matches()).isTrue();
                assertThat(a.path()).isNotEqualTo(b.path());
                assertThat(Files.size(a.path())).isEqualTo(0L);
            }
        } finally {
            delete(dir);
        }
    }

    @Test
    void closingAStagedFileDeletesIt() throws IOException {
        Path dir = Files.createTempDirectory("b8-staging");
        try {
            ArtifactStaging staging = new ArtifactStaging(dir);
            Path path;
            try (ArtifactStaging.StagedFile f = staging.create(); OutputStream out = f.openOutput()) {
                out.write(new byte[] {1, 2, 3});
                path = f.path();
                assertThat(Files.size(path)).isEqualTo(3L);
            }
            assertThat(Files.exists(path)).isFalse();
        } finally {
            delete(dir);
        }
    }

    @Test
    void theSweepDeletesOnlyOldStagedFiles() throws IOException {
        Path dir = Files.createTempDirectory("b8-staging");
        try {
            ArtifactStaging staging = new ArtifactStaging(dir);
            ArtifactStaging.StagedFile old = staging.create();
            ArtifactStaging.StagedFile recent = staging.create();
            Files.setLastModifiedTime(old.path(), FileTime.from(Instant.now().minus(Duration.ofHours(2))));
            Path foreign = Files.writeString(dir.resolve("notes.txt"), "keep");
            Files.setLastModifiedTime(foreign, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
            Path sub = Files.createDirectory(dir.resolve("00000000-0000-4000-8000-000000000000.xlsx"));
            Files.setLastModifiedTime(sub, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

            assertThat(staging.sweep(Duration.ofHours(1))).isEqualTo(1);
            assertThat(Files.exists(old.path())).isFalse();
            assertThat(Files.exists(recent.path())).isTrue();
            assertThat(Files.exists(foreign)).isTrue();
            assertThat(Files.isDirectory(sub)).isTrue();
            recent.close();
        } finally {
            delete(dir);
        }
    }

    @Test
    void aSymbolicLinkIsNeverARootAndNeverSwept() throws IOException {
        Path dir = Files.createTempDirectory("b8-staging");
        try {
            Path target = Files.createDirectory(dir.resolve("target"));
            Path link;
            try {
                link = Files.createSymbolicLink(dir.resolve("link"), target);
            } catch (UnsupportedOperationException | IOException e) {
                return; // the file system (or a Windows account without the privilege) cannot create links
            }
            Path finalLink = link;
            assertThatThrownBy(() -> new ArtifactStaging(finalLink)).isInstanceOf(IOException.class);

            ArtifactStaging staging = new ArtifactStaging(dir.resolve("staging"));
            Path victim = Files.writeString(target.resolve("victim.txt"), "keep");
            Path linkedFile = Files.createSymbolicLink(
                    staging.root().resolve("00000000-0000-4000-8000-000000000001.xlsx"), victim);
            Files.setLastModifiedTime(victim, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
            assertThat(staging.sweep(Duration.ofSeconds(0))).isEqualTo(0);
            assertThat(Files.exists(victim)).isTrue();
            Files.delete(linkedFile);
        } finally {
            delete(dir);
        }
    }

    @Test
    void aRootThatIsAFileIsRefused() throws IOException {
        Path dir = Files.createTempDirectory("b8-staging");
        try {
            Path file = Files.writeString(dir.resolve("file"), "x");
            assertThatThrownBy(() -> new ArtifactStaging(file)).isInstanceOf(IOException.class);
        } finally {
            delete(dir);
        }
    }

    @Test
    void whereSupportedOnlyTheOwnerCanReadTheStagedFiles() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return; // Windows: the permissions of the parent folder apply (documented)
        }
        Path dir = Files.createTempDirectory("b8-staging");
        try {
            ArtifactStaging staging = new ArtifactStaging(dir.resolve("staging"));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(staging.root())))
                    .isEqualTo("rwx------");
            try (ArtifactStaging.StagedFile f = staging.create()) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(f.path())))
                        .isEqualTo("rw-------");
            }
        } finally {
            delete(dir);
        }
    }

    static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
