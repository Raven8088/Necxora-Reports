package com.nexora.reports.credentials.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The single place where Reports decides whether the transport of a connection to an enterprise source is acceptable
 * (A4-B5, EX-TLS-1). Pure: no Spring, no I/O.
 * <ul>
 *   <li><b>Without</b> a transport exception (every normal build): {@code sslmode=verify-full} and a session that
 *       PostgreSQL reports as encrypted, exactly as before EX-TLS-1.</li>
 *   <li><b>With</b> the transport exception of the source: {@code sslmode=disable} only, and not expired. The
 *       session encryption reported by the server is deliberately <b>ignored</b>: behind a pooler or proxy it may
 *       describe another hop, so it proves nothing about the connection of Reports and is never reported as
 *       encryption.</li>
 * </ul>
 * Only the transport is decided here: tenancy, identity, roles, schemas, RLS and isolation are checked by their
 * own code, unchanged, whatever the result.
 */
public final class TransportPolicy {

    /** Spring profile required, together with the build capability, for any transport exception. */
    public static final String DEV_PROFILE = "insecure-transport-dev";
    /** Longest validity of a transport exception, counted from the build of the application. */
    public static final Duration MAX_VALIDITY = Duration.ofDays(14);

    private TransportPolicy() {
    }

    /** Why the transport is refused. */
    public enum Refusal {
        /** No exception applies and the connection is not {@code verify-full} with an encrypted session. */
        TLS_REQUIRED,
        /** The exception of the source has expired. */
        EXCEPTION_EXPIRED,
        /** The exception of the registration and the one of the configured source are not the same authorization. */
        EXCEPTION_MISMATCH
    }

    /** A refused transport; the message is internal and never contains connection details or secrets. */
    public static final class RefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final Refusal refusal;

        public RefusedException(Refusal refusal, String internalMessage) {
            super(internalMessage);
            this.refusal = Objects.requireNonNull(refusal, "refusal");
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * Before connecting: refuses a plaintext or partial-TLS configuration without exception and an expired exception,
     * so that no unauthorized connection is ever opened.
     */
    public static void requireConnectable(DataSourceCredentials credentials, Instant now) {
        TransportException exception = credentials.transportException();
        if (exception == null) {
            if (!"verify-full".equals(credentials.sslMode())) {
                throw new RefusedException(Refusal.TLS_REQUIRED, "sslmode is not verify-full");
            }
            return;
        }
        requireUsable(credentials, exception, now);
    }

    /**
     * After connecting: the transport of this session.
     *
     * @param sessionEncrypted what the server reports for this session; only meaningful without exception
     * @return the exception the session relies on, or empty for a verified {@code verify-full} session
     */
    public static Optional<TransportException> assess(DataSourceCredentials credentials, boolean sessionEncrypted,
                                                      Instant now) {
        TransportException exception = credentials.transportException();
        if (exception == null) {
            if (!"verify-full".equals(credentials.sslMode())) {
                throw new RefusedException(Refusal.TLS_REQUIRED, "sslmode is not verify-full");
            }
            if (!sessionEncrypted) {
                throw new RefusedException(Refusal.TLS_REQUIRED, "session is not encrypted");
            }
            return Optional.empty();
        }
        requireUsable(credentials, exception, now);
        return Optional.of(exception);
    }

    /**
     * The registration and the run (or the approval) must rely on the same transport: both verified, or both on the
     * same exception. A TLS registration never authorizes a plaintext run, and a registration captured in plaintext
     * never becomes a TLS one: the source must then be registered and approved again.
     */
    public static void requireSame(TransportException registered, TransportException current) {
        if (!TransportException.same(registered, current)) {
            throw new RefusedException(Refusal.EXCEPTION_MISMATCH,
                    "the transport differs from the one of the tenancy registration");
        }
    }

    private static void requireUsable(DataSourceCredentials credentials, TransportException exception, Instant now) {
        if (!"disable".equals(credentials.sslMode())) {
            throw new RefusedException(Refusal.TLS_REQUIRED, "a transport exception applies only to sslmode=disable");
        }
        if (!now.isBefore(exception.expiresAt())) {
            throw new RefusedException(Refusal.EXCEPTION_EXPIRED, "the transport exception has expired");
        }
    }
}
