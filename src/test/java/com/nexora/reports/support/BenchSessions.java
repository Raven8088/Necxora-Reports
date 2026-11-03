package com.nexora.reports.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * B10-O3 (D-O3-4): physical connections to the synthetic bench, observed in PostgreSQL itself, independently of
 * Reports' own counters. Two measures, both read as superuser from <b>another</b> database of the same server (so
 * that the observer never counts itself):
 * <ul>
 *   <li>{@link #settledSessions()}: {@code pg_stat_database.sessions} of the bench, the cumulative number of sessions
 *       established to it. PostgreSQL publishes the counter of a backend when it ends a transaction and goes idle,
 *       possibly up to 10 s later ({@code PGSTAT_IDLE_INTERVAL}), so the value is taken only once it has not changed
 *       for {@link #QUIET} (controlled condition); if it never settles the test fails instead of guessing;</li>
 *   <li>{@link #backendsStartedSince(String)}: backends connected to the bench that started after a server instant,
 *       from {@code pg_stat_activity} (immediate, but it only sees connections that are still open; the pools of
 *       Reports keep a used connection open, idle, for minutes).</li>
 * </ul>
 * Neither proves anything about connections to other databases. Test sources only.
 */
public final class BenchSessions {

    /** Longer than PostgreSQL's idle publication interval of backend statistics (10 s). */
    static final Duration QUIET = Duration.ofSeconds(12);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);
    private static final String OBSERVER_DATABASE = "nexora_tenancy";

    private BenchSessions() {
    }

    /** The server's current instant, as text for {@link #backendsStartedSince}. */
    public static String now() throws SQLException {
        try (Connection c = TenantDatabase.superuser(OBSERVER_DATABASE); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT clock_timestamp()::text")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Backends of the bench whose session started at or after {@code instant} and are still connected. */
    public static int backendsStartedSince(String instant) throws SQLException {
        try (Connection c = TenantDatabase.superuser(OBSERVER_DATABASE);
             PreparedStatement s = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname = ?"
                     + " AND backend_start >= ?::timestamptz")) {
            s.setString(1, TlsBench.DATABASE);
            s.setString(2, instant);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** {@code pg_stat_database.sessions} of the bench once it has not changed for {@link #QUIET}. */
    public static long settledSessions() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        long value = sessions();
        long stableSince = System.nanoTime();
        while (System.nanoTime() - stableSince < QUIET.toNanos()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("pg_stat_database.sessions of the bench did not settle in " + MAX_WAIT);
            }
            Thread.sleep(500);
            long now = sessions();
            if (now != value) {
                value = now;
                stableSince = System.nanoTime();
            }
        }
        return value;
    }

    private static long sessions() throws SQLException {
        try (Connection c = TenantDatabase.superuser(OBSERVER_DATABASE);
             PreparedStatement s = c.prepareStatement("SELECT sessions FROM pg_stat_database WHERE datname = ?")) {
            s.setString(1, TlsBench.DATABASE);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no statistics for the bench database");
                }
                return rs.getLong(1);
            }
        }
    }
}
