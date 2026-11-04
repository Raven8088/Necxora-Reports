package com.nexora.reports.credentials.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Properties;

/**
 * EX-TLS-1, factor F1: whether THIS BUILD can accept a transport exception at all. The capability is a resource
 * that only the Maven profile {@code dev-insecure-transport} puts in the jar ({@link #RESOURCE}), with the instant of
 * the build. A normal build (the one for production) has no such resource: any transport exception in its
 * configuration stops the application at startup, whatever the Spring profiles.
 *
 * @param capable whether the build was made with the profile
 * @param builtAt instant of that build; the expiry of an exception is capped from it
 */
public record TransportCapability(boolean capable, Instant builtAt) {

    public static final String RESOURCE = "META-INF/nexora/insecure-transport.properties";
    public static final TransportCapability NONE = new TransportCapability(false, null);

    public TransportCapability {
        if (capable) {
            Objects.requireNonNull(builtAt, "builtAt");
        }
    }

    /** Reads the capability of the running build; absent resource = {@link #NONE}. A damaged one is an error. */
    public static TransportCapability load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return NONE;
            }
            Properties p = new Properties();
            p.load(in);
            if (!"true".equals(p.getProperty("capable"))) {
                throw new IllegalStateException("damaged insecure-transport capability resource");
            }
            return new TransportCapability(true, Instant.parse(p.getProperty("built-at", "")));
        } catch (IOException | DateTimeParseException e) {
            throw new IllegalStateException("unreadable insecure-transport capability resource", e);
        }
    }
}
