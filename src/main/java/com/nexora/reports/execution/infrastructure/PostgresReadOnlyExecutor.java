package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.ReadOnlyExecutor;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.QueryResult;
import com.nexora.reports.translation.domain.ResultColumn;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation.domain.SqlQuery;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Executes one translated query against PostgreSQL with independent read-only defenses:
 * <ol>
 *   <li>the connection is opened read-only ({@code default_transaction_read_only=on}, JDBC read-only mode
 *       {@code always}) with server-side {@code statement_timeout} and {@code lock_timeout};</li>
 *   <li>each execution runs in a REPEATABLE READ, read-only transaction whose timeouts are set again with
 *       {@code SET LOCAL} semantics and which is always rolled back;</li>
 *   <li>the session is verified to be read-only and the role not to be a superuser;</li>
 *   <li>the live schema is verified ({@link SchemaVerifier}), including the primary keys that make joins safe and
 *       the absence of write privileges on the relations read;</li>
 *   <li>a single prepared statement is executed (extended protocol: one statement only), with fetch size, row
 *       limit and a wall-clock budget;</li>
 *   <li>concurrency is bounded globally and per data source.</li>
 * </ol>
 * Errors are mapped to stable codes; SQL text, values and connection details are never propagated.
 */
public class PostgresReadOnlyExecutor implements ReadOnlyExecutor {

    private final ConnectionPools pools;
    private final Semaphore global;
    private final Map<UUID, Semaphore> perSource = new ConcurrentHashMap<>();
    private final SchemaVerifier verifier = new SchemaVerifier();

    public PostgresReadOnlyExecutor(ConnectionPools pools, int maxConcurrentExecutions) {
        this.pools = pools;
        this.global = new Semaphore(Math.max(1, maxConcurrentExecutions), true);
    }

    @Override
    public QueryResult execute(DataSourceCredentials credentials, SqlQuery query, ExecutionLimits limits) {
        Semaphore source = perSource.computeIfAbsent(credentials.dataSourceId(),
                id -> new Semaphore(credentials.maxConcurrent(), true));
        acquire(global, limits);
        try {
            acquire(source, limits);
            try {
                return run(credentials, query, limits);
            } finally {
                source.release();
            }
        } finally {
            global.release();
        }
    }

    private static void acquire(Semaphore semaphore, ExecutionLimits limits) {
        try {
            if (!semaphore.tryAcquire(limits.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ExecutionException(ErrorCode.TOO_MANY_EXECUTIONS, "no execution slot available");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "interrupted while waiting for a slot");
        }
    }

    private QueryResult run(DataSourceCredentials credentials, SqlQuery query, ExecutionLimits limits) {
        long deadline = System.nanoTime() + limits.maxDuration().toNanos();
        Connection connection;
        try {
            connection = pools.pool(credentials).getConnection();
        } catch (SQLException e) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "cannot connect (" + state(e) + ")", e);
        }
        try (connection) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                configureTransaction(connection, limits);
                verifySession(connection);
                verifier.verify(connection, query.schema());
                checkDeadline(deadline);
                return query(connection, query, limits, deadline);
            } finally {
                rollbackQuietly(connection);
            }
        } catch (SQLException e) {
            throw map(e);
        }
    }

    private static void configureTransaction(Connection connection, ExecutionLimits limits) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true),"
                        + " set_config('TimeZone', 'UTC', true)")) {
            ps.setString(1, Long.toString(limits.statementTimeout().toMillis()));
            ps.setString(2, Long.toString(limits.lockTimeout().toMillis()));
            ps.executeQuery().close();
        }
    }

    private static void verifySession(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT current_setting('transaction_read_only'), r.rolsuper"
                        + " FROM pg_catalog.pg_roles r WHERE r.rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !"on".equals(rs.getString(1)) || rs.getBoolean(2)) {
                throw new ExecutionException(ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY,
                        "session not read-only or role is a superuser");
            }
        }
    }

    private static QueryResult query(Connection connection, SqlQuery query, ExecutionLimits limits, long deadline)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(query.sql(), ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(Math.min(limits.fetchSize(), query.maxRows() + 1));
            ps.setMaxRows(query.maxRows() + 1);
            ps.setQueryTimeout((int) Math.max(1, limits.statementTimeout().toSeconds() + 1));
            bind(ps, query.parameters());
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.getMetaData().getColumnCount() != query.columns().size()) {
                    throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "unexpected result shape");
                }
                while (rs.next()) {
                    if (rows.size() == query.maxRows()) {
                        truncated = true;
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        ps.cancel();
                        throw new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, "duration limit exceeded");
                    }
                    rows.add(row(rs, query.columns()));
                }
            }
            checkDeadline(deadline);
            return new QueryResult(rows, truncated);
        }
    }

    private static void bind(PreparedStatement ps, List<SqlParameter> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            SqlParameter p = parameters.get(i);
            int index = i + 1;
            switch (p.type()) {
                case TEXT -> ps.setString(index, (String) p.value());
                case NUMERIC -> ps.setBigDecimal(index, (BigDecimal) p.value());
                case BOOLEAN -> ps.setBoolean(index, (Boolean) p.value());
                case BIGINT -> ps.setLong(index, (Long) p.value());
                case DATE -> ps.setObject(index, (LocalDate) p.value());
                case TIMESTAMP -> ps.setObject(index, (LocalDateTime) p.value());
                case TIMESTAMP_TZ -> ps.setObject(index, (OffsetDateTime) p.value());
                case TIME -> ps.setObject(index, (LocalTime) p.value());
                case UUID -> ps.setObject(index, (UUID) p.value());
            }
        }
    }

    private static List<Object> row(ResultSet rs, List<ResultColumn> columns) throws SQLException {
        List<Object> row = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            row.add(value(rs, i + 1, columns.get(i).type()));
        }
        return row;
    }

    private static Object value(ResultSet rs, int index, ResultColumn.OutputType type) throws SQLException {
        return switch (type) {
            case TEXT -> rs.getString(index);
            case NUMERIC -> numeric(rs, index);
            case INTEGER -> {
                long value = rs.getLong(index);
                yield rs.wasNull() ? null : value;
            }
            case BOOLEAN -> {
                boolean value = rs.getBoolean(index);
                yield rs.wasNull() ? null : value;
            }
            case DATE -> text(rs.getObject(index, LocalDate.class));
            case TIMESTAMP -> text(rs.getObject(index, LocalDateTime.class));
            case TIMESTAMP_TZ -> {
                OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
                yield value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
            }
            case TIME -> text(rs.getObject(index, LocalTime.class));
            case UUID -> text(rs.getObject(index, UUID.class));
        };
    }

    /** Exact decimal text; special floating-point values (NaN, Infinity) are returned as PostgreSQL prints them. */
    private static String numeric(ResultSet rs, int index) throws SQLException {
        String raw = rs.getString(index);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw).toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, "duration limit exceeded");
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection is being returned to the pool or discarded; nothing was written.
        }
    }

    private static String state(SQLException e) {
        return e.getSQLState() == null ? "no state" : e.getSQLState();
    }

    static ExecutionException map(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String reason = "SQLSTATE " + (state.isEmpty() ? "none" : state);
        if (e instanceof SQLTransientConnectionException || state.startsWith("08") || state.startsWith("28")
                || state.equals("3D000") || state.equals("53300") || state.equals("57P01")) {
            return new ExecutionException(ErrorCode.DATA_SOURCE_UNAVAILABLE, reason, e);
        }
        if (state.equals("57014") || state.equals("55P03")) {
            return new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, reason, e);
        }
        if (state.equals("42P01") || state.equals("42703") || state.equals("3F000")) {
            return new ExecutionException(ErrorCode.PHYSICAL_SCHEMA_MISMATCH, reason, e);
        }
        if (state.equals("42501")) {
            return new ExecutionException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "insufficient privilege", e);
        }
        if (state.equals("25006")) {
            return new ExecutionException(ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY, reason, e);
        }
        return new ExecutionException(ErrorCode.EXECUTION_FAILED, reason, e);
    }
}
