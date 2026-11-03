package com.nexora.reports.support;

import org.openjdk.jol.vm.VM;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A4-B10-c.2: checks, class by class, that the sizes JOL uses are the sizes the running JVM really gives its objects,
 * before any of them is added up. JOL computes them from field offsets; for records it needs
 * {@code -Djol.magicFieldOffset=true} (Java 16+ refuses the usual way), so a wrong offset must not go unnoticed.
 * <p>
 * The reference is, in this order:
 * <ol>
 *   <li>{@link Instrumentation#getObjectSize}, the JVM's own size, when an {@code Instrumentation} can be obtained from
 *       the Byte Buddy agent that Mockito already loads in the test JVM (looked up by reflection; nothing new is
 *       added to the build);</li>
 *   <li>otherwise, the bytes the JVM accounts to this thread for allocating {@value #SAMPLES} objects of the class
 *       ({@code com.sun.management.ThreadMXBean}): an object without its constructor ({@code Unsafe.allocateInstance})
 *       or an array of each checked length; the smallest of {@value #ROUNDS} rounds, since anything else allocated
 *       meanwhile can only add bytes.</li>
 * </ol>
 * A class whose size cannot be checked, or whose JOL size differs from the reference, fails the measurement: never
 * skipped. Arrays are checked at several lengths. Test sources only; results are kept for the evidence.
 */
public final class JolLayoutCheck {

    static final int SAMPLES = 1_024;
    static final int ROUNDS = 5;
    private static final int[] ARRAY_LENGTHS = {0, 1, 2, 3, 7, 8, 9, 31};

    private static final Map<String, Object> CHECKED = new TreeMap<>();
    private static final Instrumentation INSTRUMENTATION;
    private static final String INSTRUMENTATION_MISSING;
    private static final MethodHandle ALLOCATE_INSTANCE;

    static {
        Instrumentation instrumentation = null;
        String missing = null;
        try {
            Class<?> agent = Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
            instrumentation = (Instrumentation) agent.getMethod("install").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            missing = e.getClass().getName();
        }
        INSTRUMENTATION = instrumentation;
        INSTRUMENTATION_MISSING = missing;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            ALLOCATE_INSTANCE = MethodHandles.lookup()
                    .unreflect(unsafeClass.getMethod("allocateInstance", Class.class)).bindTo(field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private JolLayoutCheck() {
    }

    /** Checks every class not checked yet; fails listing every difference. */
    public static synchronized void verify(Set<Class<?>> classes) {
        List<String> differences = new ArrayList<>();
        for (Class<?> c : classes) {
            if (CHECKED.containsKey(c.getName())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            if (c.isArray()) {
                for (int length : ARRAY_LENGTHS) {
                    Object array = Array.newInstance(c.getComponentType(), length);
                    compare(c.getName() + "[" + length + "]", VM.current().sizeOf(array),
                            reference(array, () -> Array.newInstance(c.getComponentType(), length)), entry,
                            differences);
                }
            } else {
                Object instance = allocate(c);
                compare(c.getName(), VM.current().sizeOf(instance), reference(instance, () -> allocate(c)), entry,
                        differences);
            }
            CHECKED.put(c.getName(), entry);
        }
        if (!differences.isEmpty()) {
            throw new AssertionError("JOL sizes differ from the JVM's: " + differences);
        }
    }

    /** What was checked and against which reference, for the evidence. */
    public static synchronized Map<String, Object> summary() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reference", INSTRUMENTATION != null ? "Instrumentation.getObjectSize (Byte Buddy agent)"
                : "bytes allocated by the thread (ThreadMXBean), smallest of " + ROUNDS + " rounds of " + SAMPLES
                + " objects per size");
        map.put("instrumentationUnavailableBecause", INSTRUMENTATION_MISSING);
        map.put("jolMagicFieldOffset", System.getProperty("jol.magicFieldOffset"));
        map.put("classesChecked", (long) CHECKED.size());
        map.put("classes", new TreeMap<>(CHECKED));
        return map;
    }

    private static void compare(String what, long jol, long reference, Map<String, Object> entry,
                                List<String> differences) {
        entry.put(what, jol == reference ? Long.valueOf(jol) : "JOL " + jol + " / JVM " + reference);
        if (jol != reference) {
            differences.add(what + ": JOL " + jol + ", JVM " + reference);
        }
    }

    private static long reference(Object sample, java.util.function.Supplier<Object> allocator) {
        if (INSTRUMENTATION != null) {
            return INSTRUMENTATION.getObjectSize(sample);
        }
        return allocatedSize(allocator);
    }

    /** Bytes per object the JVM accounts for allocating {@link #SAMPLES} of them, kept alive in a ready array. */
    private static long allocatedSize(java.util.function.Supplier<Object> allocator) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean)
                ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported() || !threads.isThreadAllocatedMemoryEnabled()) {
            throw new AssertionError("no reference to check JOL against: neither Instrumentation ("
                    + INSTRUMENTATION_MISSING + ") nor per-thread allocation accounting");
        }
        // Anything else the thread allocates meanwhile can only add bytes: the smallest of several rounds is kept.
        Object[] keep = new Object[SAMPLES];
        long smallest = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < SAMPLES; i++) {
                keep[i] = allocator.get();
            }
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            if (keep[SAMPLES - 1] == null) {
                throw new AssertionError("allocation was not kept");
            }
            smallest = Math.min(smallest, allocated / SAMPLES);
        }
        return smallest;
    }

    private static Object allocate(Class<?> c) {
        try {
            return (Object) ALLOCATE_INSTANCE.invokeExact(c);
        } catch (Throwable e) {
            throw new AssertionError("cannot allocate " + c.getName() + " to check its size", e);
        }
    }
}
