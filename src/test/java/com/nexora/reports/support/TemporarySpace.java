package com.nexora.reports.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * A4-B10-c.2, C2-3: the temporary space of Reports (its storage directory: {@code staging/} and {@code poifiles/},
 * plus the directory POI is currently using) seen from outside, without touching production.
 * <p>
 * {@link #snapshot} lists every regular file and its size. {@link #sample} watches the directories from another
 * thread at a fixed interval and keeps the largest total and the most files seen: <b>a lower bound</b> of the real
 * peak, since a peak shorter than the interval can be missed. Test sources only.
 */
public final class TemporarySpace {

    private TemporarySpace() {
    }

    /** Regular files under the roots and their sizes, by path. */
    public record Snapshot(Map<String, Long> files) {

        public long bytes() {
            return files.values().stream().mapToLong(Long::longValue).sum();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("files", (long) files.size());
            map.put("bytes", bytes());
            map.put("names", new ArrayList<>(files.keySet()));
            return map;
        }
    }

    public static Snapshot snapshot(List<Path> roots) {
        Map<String, Long> files = new TreeMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.toList()) {
                    try {
                        if (Files.isRegularFile(p)) {
                            files.put(root.relativize(p).toString().replace('\\', '/') + " @" + root.getFileName(),
                                    Files.size(p));
                        }
                    } catch (NoSuchFileException gone) {
                        // deleted while listed: not there any more
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                // a file disappeared while walking: the next sample sees the directory again
            }
        }
        return new Snapshot(files);
    }

    /** A sampler running until {@link Sampler#stop()}. */
    public static Sampler sample(Duration interval, List<Path> roots) {
        Sampler sampler = new Sampler(interval, roots);
        sampler.thread.start();
        return sampler;
    }

    public static final class Sampler {
        private final Duration interval;
        private final List<Path> roots;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;
        private volatile long maxBytes;
        private volatile long maxFiles;
        private volatile long samples;

        private Sampler(Duration interval, List<Path> roots) {
            this.interval = interval;
            this.roots = List.copyOf(roots);
            this.thread = new Thread(this::loop, "b10c2-temporary-space-sampler");
            this.thread.setDaemon(true);
        }

        private void loop() {
            while (running.get()) {
                Snapshot s = snapshot(roots);
                maxBytes = Math.max(maxBytes, s.bytes());
                maxFiles = Math.max(maxFiles, s.files().size());
                samples++;
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        /** Stops sampling; the largest values observed are a lower bound of the real peak. */
        public Map<String, Object> stop() throws InterruptedException {
            running.set(false);
            thread.join(5_000);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("maxObservedBytes", maxBytes);
            map.put("maxObservedFiles", maxFiles);
            map.put("samples", samples);
            map.put("intervalMillis", interval.toMillis());
            map.put("meaning", "lower bound of the peak: a peak shorter than the interval can be missed");
            return map;
        }
    }
}
