package com.nexora.reports.render.xlsx;

import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A4-B9 (D-B9-8, B7-O3): where Apache POI writes the temporary files of the streaming writer. POI's strategy is global
 * to the JVM, so Reports fixes it once at startup to a directory of its own storage; until then (and in tests that do
 * not start Reports) POI uses its default, {@code java.io.tmpdir/poifiles}. The files are deleted by the writer in
 * every case (A4-B7, precision 8); the directory only keeps them out of a shared temporary folder.
 */
public final class PoiTemporaryFiles {

    private static volatile Path directory = Path.of(System.getProperty("java.io.tmpdir"),
            DefaultTempFileCreationStrategy.POIFILES);

    private PoiTemporaryFiles() {
    }

    /** Points POI's temporary files to {@code dir}, created if needed; it must be a real directory, not a link. */
    public static synchronized void use(Path dir) throws IOException {
        Path absolute = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("the POI temporary directory must not be a symbolic link");
        }
        Files.createDirectories(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the POI temporary directory is not a directory");
        }
        TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy(absolute.toFile()));
        directory = absolute;
    }

    /** The directory POI currently writes its temporary files to. */
    public static Path directory() {
        return directory;
    }
}
