package com.nexora.reports.support;

import com.nexora.reports.execution.application.port.CredentialProvider;
import com.nexora.reports.execution.application.port.ReportPlanSource;
import com.nexora.reports.execution.application.port.SourceAvailability;
import com.nexora.reports.execution.application.port.TenantScopes;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A4-B10 (T-5, T-9, T-11): observes, in a Spring test, what a request reached inside Reports, without changing what
 * production does. The ports {@link SourceAvailability}, {@link CredentialProvider}, {@link TenantScopes} and
 * {@link ReportPlanSource} are wrapped in proxies that count calls and delegate to the real adapters:
 * <ul>
 *   <li>B10-O3 (D-O3-4): destinations looked up without their secret ({@code configured}, step 2 of a run) and
 *       credentials resolved ({@code resolve}, right before a read), counted apart. One bean implements both
 *       ports, so its proxy implements every interface of the bean;</li>
 *   <li>reads started: the tenant scope check of an execution is built right before the executor is called, and the
 *       executor opens no connection without it, so zero means no connection was opened for a run;</li>
 *   <li>an unexpected failure (not an {@code ExecutionException}) can be injected into one call to Semantic, to
 *       reproduce failures nobody anticipated.</li>
 * </ul>
 * Calls to Semantic itself are recorded by {@link TestPlatform}. Test sources only.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RunProbes {

    private static final AtomicInteger AVAILABILITY = new AtomicInteger();
    private static final AtomicInteger CREDENTIALS = new AtomicInteger();
    private static final AtomicInteger READS = new AtomicInteger();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();

    public static void reset() {
        AVAILABILITY.set(0);
        CREDENTIALS.set(0);
        READS.set(0);
        CALLS.clear();
        FAILURES.clear();
        TestPlatform.clearCalls();
    }

    /** Forgets what was counted (credentials, reads, calls to Semantic) but keeps the failures still to inject. */
    public static void resetCounts() {
        AVAILABILITY.set(0);
        CREDENTIALS.set(0);
        READS.set(0);
        TestPlatform.clearCalls();
    }

    /** Calls to {@code SourceAvailability.configured}: a destination looked up, no secret resolved. */
    public static int availabilityQueries() {
        return AVAILABILITY.get();
    }

    /** Calls to {@code CredentialProvider.resolve}: the credentials (with their secret) resolved. */
    public static int credentialsResolved() {
        return CREDENTIALS.get();
    }

    public static int readsStarted() {
        return READS.get();
    }

    /**
     * The {@code call}-th call (1-based) of {@code method} of {@link ReportPlanSource} for {@code reportPlanId}
     * throws an {@link IllegalStateException} with {@code message}.
     */
    public static void failSemanticCall(String method, UUID reportPlanId, int call, String message) {
        FAILURES.put(method + " " + reportPlanId + " " + call, message);
    }

    @Bean
    static BeanPostProcessor runProbesPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof CredentialProvider || bean instanceof SourceAvailability) {
                    return proxyAll(bean, (method, args) -> {
                        if ("resolve".equals(method)) {
                            CREDENTIALS.incrementAndGet();
                        } else if ("configured".equals(method)) {
                            AVAILABILITY.incrementAndGet();
                        }
                    });
                }
                if (bean instanceof TenantScopes scopes) {
                    return proxy(TenantScopes.class, scopes, (method, args) -> {
                        if ("forExecution".equals(method)) {
                            READS.incrementAndGet();
                        }
                    });
                }
                if (bean instanceof ReportPlanSource semantic) {
                    return proxy(ReportPlanSource.class, semantic, RunProbes::injectFailure);
                }
                return bean;
            }
        };
    }

    private static void injectFailure(String method, Object[] args) {
        if (args == null || args.length < 3 || !(args[2] instanceof UUID reportPlanId)) {
            return;
        }
        String key = method + " " + reportPlanId;
        int call = CALLS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String message = FAILURES.get(key + " " + call);
        if (message != null) {
            throw new IllegalStateException(message);
        }
    }

    private interface Before {
        void accept(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, T target, Before before) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler(target, before)));
    }

    /** A proxy implementing every interface of {@code target} (and of its superclasses). */
    private static Object proxyAll(Object target, Before before) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            interfaces.addAll(List.of(c.getInterfaces()));
        }
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces.toArray(Class<?>[]::new),
                handler(target, before));
    }

    private static InvocationHandler handler(Object target, Before before) {
        return (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            before.accept(method.getName(), args);
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
    }
}
