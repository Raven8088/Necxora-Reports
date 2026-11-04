package com.nexora.reports.render.xlsx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Writes {@code grid/<name>.grid} for every {@link GridFixtures} fixture with the renderer on the classpath. It was
 * run ONCE with the renderer before the presentation change (D-XL-1); {@code PresentationGridTest} compares with it.
 */
public final class GridBaselines {

    private GridBaselines() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        for (Map.Entry<String, Supplier<byte[]>> f : GridFixtures.all().entrySet()) {
            String grid = GridSnapshot.of(f.getValue().get());
            Files.writeString(dir.resolve(f.getKey() + ".grid"), grid, StandardCharsets.UTF_8);
            System.out.println(f.getKey() + " " + grid.lines().count() + " lines");
        }
    }
}
