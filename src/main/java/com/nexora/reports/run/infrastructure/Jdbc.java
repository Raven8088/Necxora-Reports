package com.nexora.reports.run.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Small JDBC helpers of the run registry. */
final class Jdbc {

    static final String UNIQUE_VIOLATION = "23505";
    static final String FOREIGN_KEY_VIOLATION = "23503";

    private Jdbc() {
    }

    static boolean is(SQLException e, String state) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && state.equals(s.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    static void rollbackQuietly(Connection c) {
        try {
            if (!c.getAutoCommit()) {
                c.rollback();
            }
        } catch (SQLException ignored) {
            // the connection is broken: nothing was committed
        }
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        throw new IllegalStateException("unexpected timestamp type");
    }
}
