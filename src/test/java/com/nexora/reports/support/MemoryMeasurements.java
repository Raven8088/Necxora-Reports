package com.nexora.reports.support;

import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.ResultSizeEstimate;
import com.nexora.reports.plan2.domain.CanonicalJson;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A4-B10-c.2 (D-C2-1): memory measurements of tests, never of production. Test sources only.
 * <p>
 * <b>Retained size</b> ({@link #retained}): the real layout of the running JVM measured by JOL
 * ({@link GraphLayout#parseInstance}), every object counted once by identity. The only objects that may be discounted
 * are instances that the JDK caches and shares by value, so that they exist whether or not a result is read
 * ({@link #sharedJdkInstance}): {@code Boolean.TRUE/FALSE}, the {@code Long} cache (-128..127), the {@code ZoneOffset}
 * cache and the whole-hour {@code LocalTime} cache. An object is discounted only when it is identical ({@code ==}) to
 * the instance the public cache returns for its value, together with the objects it holds (the id of a cached
 * {@code ZoneOffset}); everything else is counted, so the attributable size is never below what the result really
 * holds. The figure without any discount is always recorded too.
 * <p>
 * Before adding them up, every size JOL uses is checked against the JVM's own ({@link JolLayoutCheck}); a
 * difference fails the measurement.
 * <p>
 * <b>Informative magnitudes</b> ({@link Probe}): bytes allocated by the measuring thread and the peak of the heap
 * pools. They include garbage and other threads; they are recorded, never asserted.
 */
public final class MemoryMeasurements {

    public static final Path EVIDENCE = Path.of("target", "b10c2-evidence");

    private MemoryMeasurements() {
    }

    /**
     * Retained size of the graph reachable from {@code roots}; {@code values} are the values whose shared JDK
     * instances (themselves or their parts) may be discounted.
     *
     * @param total        bytes of every distinct object reachable from the roots, as JOL measures them
     * @param shared       bytes of the distinct shared JDK instances found among them (discounted)
     * @param attributable {@code total - shared}
     */
    public record Retained(long total, long objects, long shared, long sharedObjects, long attributable) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalBytes", total);
            map.put("objects", objects);
            map.put("sharedJdkBytes", shared);
            map.put("sharedJdkObjects", sharedObjects);
            map.put("attributableBytes", attributable);
            return map;
        }
    }

    /**
     * Measures twice and requires the same figures: a graph that changes while it is walked is not a measurement.
     */
    public static Retained retained(List<?> roots, Iterable<?> values) {
        Retained first = measure(roots, values);
        Retained second = measure(roots, values);
        if (!first.equals(second)) {
            throw new AssertionError("unstable measurement: " + first + " then " + second);
        }
        return first;
    }

    private static Retained measure(List<?> roots, Iterable<?> values) {
        GraphLayout layout = GraphLayout.parseInstance(roots.toArray());
        // Every size JOL added up is first checked against the JVM's own (records need jol.magicFieldOffset).
        JolLayoutCheck.verify(layout.getClasses());
        Map<Object, Boolean> shared = new IdentityHashMap<>();
        for (Object value : values) {
            for (Object part : parts(value)) {
                if (sharedJdkInstance(part)) {
                    shared.put(part, Boolean.TRUE);
                }
            }
        }
        // A shared instance is discounted with what only it holds (for example the id of a cached ZoneOffset).
        GraphLayout sharedLayout = shared.isEmpty() ? null : GraphLayout.parseInstance(shared.keySet().toArray());
        long sharedBytes = sharedLayout == null ? 0 : sharedLayout.totalSize();
        long sharedObjects = sharedLayout == null ? 0 : sharedLayout.totalCount();
        if (sharedLayout != null) {
            JolLayoutCheck.verify(sharedLayout.getClasses());
        }
        return new Retained(layout.totalSize(), layout.totalCount(), sharedBytes, sharedObjects,
                layout.totalSize() - sharedBytes);
    }

    /** A value and the objects it holds that a JDK cache may share. */
    private static List<Object> parts(Object value) {
        List<Object> parts = new ArrayList<>(3);
        if (value == null) {
            return parts;
        }
        parts.add(value);
        if (value instanceof LocalDateTime t) {
            parts.add(t.toLocalTime());
        } else if (value instanceof OffsetDateTime t) {
            parts.add(t.getOffset());
            parts.add(t.toLocalTime());
        }
        return parts;
    }

    /** True only for the very instance a public JDK cache returns for this value. */
    public static boolean sharedJdkInstance(Object o) {
        return switch (o) {
            case Boolean b -> o == Boolean.valueOf(b);
            case Long l -> l >= -128 && l <= 127 && o == Long.valueOf(l);
            case ZoneOffset z -> o == ZoneOffset.ofTotalSeconds(z.getTotalSeconds());
            case LocalTime t -> t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0
                    && o == LocalTime.of(t.getHour(), 0);
            default -> false;
        };
    }

    /**
     * {@code E}: the estimate production adds up while reading ({@code PlanV2SnapshotReader.Totals}): the cost of
     * each dataset ({@link ResultSizeEstimate#DATASET}) plus that of each of its rows, with the same saturating sum.
     */
    public static long estimate(List<DatasetRows> datasets) {
        long bytes = 0;
        for (DatasetRows dataset : datasets) {
            bytes = ResultSizeEstimate.sum(bytes, ResultSizeEstimate.DATASET);
            for (List<Object> row : dataset.rows()) {
                bytes = ResultSizeEstimate.sum(bytes, ResultSizeEstimate.ofRow(row));
            }
        }
        return bytes;
    }

    /** {@code comprimidas} or {@code sin-comprimir}: whether the running HotSpot uses compressed references. */
    public static String referenceMode() {
        com.sun.management.HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(
                com.sun.management.HotSpotDiagnosticMXBean.class);
        return "true".equals(hotspot.getVMOption("UseCompressedOops").getValue()) ? "comprimidas" : "sin-comprimir";
    }

    /** Every value of the rows, for {@link #retained}. */
    public static List<Object> values(Iterable<? extends List<Object>> rows) {
        List<Object> values = new ArrayList<>();
        for (List<Object> row : rows) {
            values.addAll(row);
        }
        return values;
    }

    // ------------------------------------------------------------------ informative magnitudes

    /** Allocation of the current thread and peak of the heap pools between {@link #start()} and {@link #stop()}. */
    public static final class Probe {
        private final com.sun.management.ThreadMXBean threads;
        private final long allocatedBefore;
        private final long heapUsedBefore;

        private Probe() {
            threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            for (MemoryPoolMXBean pool : heapPools()) {
                pool.resetPeakUsage();
            }
            heapUsedBefore = heapPools().stream().mapToLong(p -> p.getUsage().getUsed()).sum();
            allocatedBefore = allocated();
        }

        public static Probe start() {
            return new Probe();
        }

        private long allocated() {
            return threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled()
                    ? threads.getCurrentThreadAllocatedBytes() : -1;
        }

        /**
         * {@code allocatedBytes}: allocated by this thread meanwhile (includes garbage; -1 if unsupported).
         * {@code heapPeakBytes}: sum of the peaks of every heap pool; the pools may peak at different moments, so it
         * is an upper bound of their simultaneous peak, and it includes other threads.
         */
        public Map<String, Object> stop() {
            long allocated = allocated();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("allocatedBytes", allocated < 0 || allocatedBefore < 0 ? -1L : allocated - allocatedBefore);
            map.put("heapUsedBeforeBytes", heapUsedBefore);
            map.put("heapPeakBytes", heapPools().stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum());
            map.put("heapUsedAfterBytes", heapPools().stream().mapToLong(p -> p.getUsage().getUsed()).sum());
            return map;
        }

        private static List<MemoryPoolMXBean> heapPools() {
            return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP)
                    .filter(MemoryPoolMXBean::isValid).toList();
        }
    }

    /** The JVM the figures belong to: the layout depends on it (compressed references, alignment). */
    public static Map<String, Object> jvm() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("javaVersion", System.getProperty("java.version"));
        map.put("vmName", runtime.getVmName());
        map.put("vmVersion", runtime.getVmVersion());
        map.put("osName", System.getProperty("os.name"));
        map.put("referenceMode", referenceMode());
        map.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        map.put("garbageCollectors", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(b -> b.getName()).toList());
        map.put("inputArguments", runtime.getInputArguments().stream()
                .filter(a -> a.startsWith("-XX") || a.startsWith("-Xm") || a.startsWith("--add-opens")).toList());
        map.put("jolDetails", VM.current().details());
        map.put("layoutCheck", JolLayoutCheck.summary());
        return map;
    }

    // ------------------------------------------------------------------ evidence

    /** Writes {@code target/b10c2-evidence/<name>} as canonical JSON and refreshes the evidence's SHA256SUMS. */
    public static synchronized void writeEvidence(String name, Map<String, Object> content) {
        try {
            Files.createDirectories(EVIDENCE);
            Files.writeString(EVIDENCE.resolve(name), CanonicalJson.write(content), StandardCharsets.UTF_8);
            List<Path> files;
            try (Stream<Path> list = Files.list(EVIDENCE)) {
                files = list.filter(p -> !p.getFileName().toString().equals("SHA256SUMS")).sorted().toList();
            }
            StringBuilder sums = new StringBuilder();
            for (Path p : files) {
                sums.append(AuthenticPlans.sha256(Files.readAllBytes(p))).append("  ").append(p.getFileName())
                        .append('\n');
            }
            Files.writeString(EVIDENCE.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A ratio as text (canonical JSON has no fractions): {@code numerator / denominator} with four decimals. */
    public static String ratio(long numerator, long denominator) {
        return denominator == 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.4f",
                (double) numerator / denominator);
    }
}
