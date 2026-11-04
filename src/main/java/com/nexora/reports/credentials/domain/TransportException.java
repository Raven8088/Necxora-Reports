package com.nexora.reports.credentials.domain;

import com.nexora.reports.plan2.domain.Sha256;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * EX-TLS-1: a temporary, explicitly authorized exception to the {@code verify-full} rule for ONE configured data
 * source and ONE destination. Under it Reports connects with {@code sslmode=disable}: neither the encryption of the
 * connection nor the identity of the server are guaranteed, whatever a VPN or {@code pg_stat_ssl} (which may describe
 * another hop, for example from a pooler to PostgreSQL) say.
 * <p>
 * It exists only in builds made with the Maven profile {@code dev-insecure-transport} and only with the Spring
 * profile {@link TransportPolicy#DEV_PROFILE}; it expires ({@link #expiresAt}) at most
 * {@link TransportPolicy#MAX_VALIDITY} after the build. {@link #binding} is the SHA-256 of the canonical description
 * of the authorized destination ({@link #canonical}): it ties the authorization to the tenant, the data source, the
 * host, the port, the database, the user, the mode, the id and the expiry. It is an integrity binding, not a
 * signature: it prevents the authorization from being applied to another destination by mistake.
 *
 * @param id        identifier of the authorization in the decision register (for example {@code EX-TLS-1})
 * @param expiresAt instant from which the exception is refused everywhere
 * @param binding   lowercase hexadecimal SHA-256 of {@link #canonical}
 */
public record TransportException(String id, Instant expiresAt, String binding) {

    public static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,31}");
    public static final Pattern BINDING = Pattern.compile("[0-9a-f]{64}");
    /** Version line of the canonical text; changing the text requires a new version. */
    public static final String CANONICAL_FORMAT = "nexora-reports-transport-exception/1";

    public TransportException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(binding, "binding");
        if (!ID.matcher(id).matches() || !BINDING.matcher(binding).matches()) {
            throw new IllegalArgumentException("invalid transport exception id or binding");
        }
    }

    /**
     * The canonical text the binding is computed from: one {@code key=value} line per element, in this order, each
     * ended by a line feed, UTF-8. No secret is part of it.
     */
    public static String canonical(UUID tenantId, UUID dataSourceId, String host, int port, String database,
                                   String username, String sslMode, String id, Instant expiresAt) {
        return CANONICAL_FORMAT + "\n"
                + "tenantId=" + tenantId + "\n"
                + "dataSourceId=" + dataSourceId + "\n"
                + "host=" + host + "\n"
                + "port=" + port + "\n"
                + "database=" + database + "\n"
                + "username=" + username + "\n"
                + "sslMode=" + sslMode + "\n"
                + "id=" + id + "\n"
                + "expiresAt=" + expiresAt + "\n";
    }

    /** The binding of a destination: lowercase hexadecimal SHA-256 of {@link #canonical}. */
    public static String binding(UUID tenantId, UUID dataSourceId, String host, int port, String database,
                                 String username, String sslMode, String id, Instant expiresAt, Sha256 sha256) {
        return sha256.hex(canonical(tenantId, dataSourceId, host, port, database, username, sslMode, id, expiresAt));
    }

    /** Whether two exceptions are the same authorization (same id, expiry and binding). {@code null} is "none". */
    public static boolean same(TransportException a, TransportException b) {
        return Objects.equals(a, b);
    }
}
