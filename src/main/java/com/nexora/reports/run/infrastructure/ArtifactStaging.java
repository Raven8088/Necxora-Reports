package com.nexora.reports.run.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reports' own directory for artifacts between rendering and storing (D-B8-4). Names are random UUIDs, never derived
 * from any input, so no path can be steered from outside; a resolved path must stay directly inside the root, which
 * must not be a symbolic link. Where the file system supports POSIX permissions the directory and files are the
 * owner's only (on Windows the permissions of the parent folder apply). {@link #sweep} removes leftovers of a crash.
 */
public final class ArtifactStaging {

    static final Pattern NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.xlsx");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path root;

    public ArtifactStaging(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("the staging root must not be a symbolic link");
        }
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (POSIX) {
                Files.createDirectories(absolute, ownerOnly("rwx------"));
            } else {
                Files.createDirectories(absolute);
            }
        }
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the staging root is not a directory");
        }
        this.root = absolute.toRealPath();
    }

    public Path root() {
        return root;
    }

    /** A new, empty file with a random name, deleted when the returned handle is closed. */
    public StagedFile create() throws IOException {
        Path file = root.resolve(UUID.randomUUID() + ".xlsx").normalize();
        if (!root.equals(file.getParent())) {
            throw new IOException("staged file outside the staging root");
        }
        if (POSIX) {
            Files.createFile(file, ownerOnly("rw-------"));
        } else {
            Files.createFile(file);
        }
        return new StagedFile(file);
    }

    /** Deletes staged files older than {@code age} (leftovers of a crash); returns how many. Never follows links. */
    public int sweep(Duration age) throws IOException {
        Instant limit = Instant.now().minus(age);
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path f : files) {
                if (NAME.matcher(f.getFileName().toString()).matches()
                        && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(f, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)
                        && Files.deleteIfExists(f)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private static FileAttribute<Set<PosixFilePermission>> ownerOnly(String permissions) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions));
    }

    /** A staged file; closing it deletes it. */
    public static final class StagedFile implements AutoCloseable {
        private final Path path;

        StagedFile(Path path) {
            this.path = path;
        }

        public Path path() {
            return path;
        }

        public OutputStream openOutput() throws IOException {
            return Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
