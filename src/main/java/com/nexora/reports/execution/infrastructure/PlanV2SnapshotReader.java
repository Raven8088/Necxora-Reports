package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.CatalogReader;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.DatasetRows;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.OutputValueType;
import com.nexora.reports.execution.domain.ResultSizeEstimate;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import com.nexora.reports.translation2.domain.SqlTextGuard;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * A4-B4: reads every dataset of one {@code /2} report plan in one connection and one transaction, with the defenses
 * of R1 ({@link PostgresReadOnlyExecutor}) and, in this order, failing closed at each step:
 * <ol>
 *   <li>REPEATABLE READ, read-only transaction; {@code statement_timeout}, {@code lock_timeout} and UTC set locally;
 *       the session must be read-only, the role not a superuser and the isolation really REPEATABLE READ;</li>
 *   <li>the mandatory tenant scope check (A4-B5), before anything else is read;</li>
 *   <li>the live schema of every query ({@link SchemaVerifier}, with column privileges), before any data is read;</li>
 *   <li>the snapshot of the transaction ({@code pg_current_snapshot()}), recorded as evidence;</li>
 *   <li>the queries in plan order: one prepared statement each, {@code statement_timeout} capped by the time left
 *       of the whole execution, typed values, the extra row that detects truncation discarded, and the total rows
 *       and estimated memory of all datasets bounded while reading;</li>
 *   <li>rollback always, and the connection returned before the result leaves this class.</li>
 * </ol>
 * Messages never contain SQL, values or connection details.
 */
final class PlanV2SnapshotReader {

    private final SchemaVerifier verifier;

    PlanV2SnapshotReader(SchemaVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    SnapshotRead read(Connection connection, DataSourceCredentials credentials, ExecutionRequestV2 request,
                      RunBudget budget, TenantScopeVerifier tenantScope, IntConsumer beforeQuery) {
        long start = System.nanoTime();
        long deadline = start + budget.maxDuration().toNanos();
        try (connection) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                timeouts(connection, budget.statementTimeout(), budget.lockTimeout());
                PostgresReadOnlyExecutor.verifySession(connection);
                verifyIsolation(connection);
                tenantScope.verify(credentials, new TransactionCatalogReader(connection));
                for (SqlQueryV2 query : request.queries()) {
                    verifier.verify(connection, query.schema());
                }
                checkDeadline(deadline);
                String snapshot = snapshot(connection);
                Totals totals = new Totals(budget);
                List<DatasetRows> datasets = new ArrayList<>();
                for (int i = 0; i < request.queries().size(); i++) {
                    beforeQuery.accept(i);
                    totals.dataset();
                    datasets.add(query(connection, request.queries().get(i), budget, deadline, totals));
                }
                return new SnapshotRead(snapshot, datasets, Duration.ofNanos(System.nanoTime() - start));
            } finally {
                PostgresReadOnlyExecutor.rollbackQuietly(connection);
            }
        } catch (SQLException e) {
            throw PostgresReadOnlyExecutor.map(e);
        }
    }

    private static void timeouts(Connection connection, Duration statement, Duration lock) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true),"
                        + " set_config('TimeZone', 'UTC', true)")) {
            ps.setString(1, Long.toString(Math.max(1, statement.toMillis())));
            ps.setString(2, Long.toString(Math.max(1, lock.toMillis())));
            ps.executeQuery().close();
        }
    }

    private static void statementTimeout(Connection connection, long millis) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            ps.setString(1, Long.toString(Math.max(1, millis)));
            ps.executeQuery().close();
        }
    }

    private static void verifyIsolation(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT current_setting('transaction_isolation')");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !"repeatable read".equals(rs.getString(1))) {
                throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "transaction is not REPEATABLE READ");
            }
        }
    }

    private static String snapshot(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_current_snapshot()::text");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || rs.getString(1) == null) {
                throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "no snapshot");
            }
            return rs.getString(1);
        }
    }

    private static DatasetRows query(Connection connection, SqlQueryV2 query, RunBudget budget, long deadline,
                                     Totals totals) throws SQLException {
        long started = System.nanoTime();
        long left = remainingMillis(deadline);
        long statement = Math.min(budget.statementTimeout().toMillis(), left);
        statementTimeout(connection, statement);
        List<OutputValueType> types = query.outputs().stream().map(o -> OutputValueType.of(o.valueType())).toList();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        try (PreparedStatement ps = connection.prepareStatement(query.sql(), ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(Math.min(budget.fetchSize(), query.maxRows() + 1));
            ps.setMaxRows(query.maxRows() + 1);
            ps.setQueryTimeout((int) Math.max(1, Duration.ofMillis(statement).toSeconds() + 1));
            PostgresReadOnlyExecutor.bind(ps, query.parameters());
            try (ResultSet rs = ps.executeQuery()) {
                checkShape(rs.getMetaData(), query.outputs());
                while (rs.next()) {
                    if (rows.size() == query.maxRows()) {
                        truncated = true;
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        ps.cancel();
                        throw new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, "duration limit exceeded");
                    }
                    List<Object> row = new ArrayList<>(types.size());
                    for (int i = 0; i < types.size(); i++) {
                        row.add(value(rs, i + 1, types.get(i)));
                    }
                    totals.add(row);
                    rows.add(row);
                }
            }
        }
        checkDeadline(deadline);
        return new DatasetRows(query.datasetId(), query.outputs(), rows, truncated,
                Duration.ofNanos(System.nanoTime() - started));
    }

    /** The result must have exactly the columns the translator produced: {@code "c1"…"cN"}, in order. */
    private static void checkShape(ResultSetMetaData metadata, List<SqlQueryV2.Output> outputs) throws SQLException {
        if (metadata.getColumnCount() != outputs.size()) {
            throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "unexpected result shape");
        }
        for (int i = 0; i < outputs.size(); i++) {
            if (!("c" + outputs.get(i).position()).equals(metadata.getColumnLabel(i + 1))) {
                throw new ExecutionException(ErrorCode.EXECUTION_FAILED, "unexpected result shape");
            }
        }
    }

    /** Typed value of a column, never through text for numbers or dates; fails closed on inexact values. */
    static Object value(ResultSet rs, int index, OutputValueType type) throws SQLException {
        return switch (type) {
            case TEXT -> rs.getString(index);
            case NUMBER -> {
                String raw = rs.getString(index);
                yield raw == null ? null : decimal(raw);
            }
            case INTEGER -> {
                String raw = rs.getString(index);
                if (raw == null) {
                    yield null;
                }
                try {
                    yield decimal(raw).longValueExact();
                } catch (ArithmeticException e) {
                    throw notRepresentable();
                }
            }
            case BOOLEAN -> {
                boolean value = rs.getBoolean(index);
                yield rs.wasNull() ? null : value;
            }
            case DATE -> {
                LocalDate value = temporal(rs, index, LocalDate.class);
                if (LocalDate.MAX.equals(value) || LocalDate.MIN.equals(value)) {
                    throw notRepresentable();
                }
                yield value;
            }
            case TIMESTAMP -> {
                LocalDateTime value = temporal(rs, index, LocalDateTime.class);
                if (LocalDateTime.MAX.equals(value) || LocalDateTime.MIN.equals(value)) {
                    throw notRepresentable();
                }
                yield value;
            }
            case TIMESTAMP_TZ -> {
                OffsetDateTime value = temporal(rs, index, OffsetDateTime.class);
                if (value == null) {
                    yield null;
                }
                if (OffsetDateTime.MAX.equals(value) || OffsetDateTime.MIN.equals(value)) {
                    throw notRepresentable();
                }
                yield value.withOffsetSameInstant(ZoneOffset.UTC);
            }
        };
    }

    /** PostgreSQL prints NaN and ±Infinity for numeric and floating types: they have no exact decimal. */
    static BigDecimal decimal(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw notRepresentable();
        }
    }

    /** {@code infinity} dates may come back as MAX/MIN or as a conversion error, depending on the driver. */
    private static <T> T temporal(ResultSet rs, int index, Class<T> type) throws SQLException {
        try {
            return rs.getObject(index, type);
        } catch (SQLException e) {
            String state = e.getSQLState();
            if (state == null || state.startsWith("22")) {
                throw notRepresentable();
            }
            throw e;
        }
    }

    private static ExecutionException notRepresentable() {
        return new ExecutionException(ErrorCode.VALUE_NOT_REPRESENTABLE, "value not representable in its type");
    }

    private static long remainingMillis(long deadline) {
        long left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
        if (left < 1) {
            throw new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, "duration limit exceeded");
        }
        return left;
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw new ExecutionException(ErrorCode.EXECUTION_TIMEOUT, "duration limit exceeded");
        }
    }

    /** Rows and estimated memory kept across all datasets of the execution. */
    private static final class Totals {
        private final RunBudget budget;
        private long rows;
        private long bytes;

        Totals(RunBudget budget) {
            this.budget = budget;
        }

        /** A4-B10-c.2: the containers of a dataset, counted once when it starts, even if it has no row. */
        void dataset() {
            bytes = ResultSizeEstimate.sum(bytes, ResultSizeEstimate.DATASET);
            checkBytes();
        }

        void add(List<Object> row) {
            rows++;
            bytes = ResultSizeEstimate.sum(bytes, ResultSizeEstimate.ofRow(row));
            if (rows > budget.maxTotalRows()) {
                throw new ExecutionException(ErrorCode.RESULT_TOO_LARGE, "total row limit exceeded");
            }
            checkBytes();
        }

        private void checkBytes() {
            if (bytes > budget.maxEstimatedBytes()) {
                throw new ExecutionException(ErrorCode.RESULT_TOO_LARGE, "memory limit exceeded");
            }
        }
    }

    /** Catalog queries for the tenant scope check, on the execution's own connection and transaction. */
    private static final class TransactionCatalogReader implements CatalogReader {
        private final Connection connection;

        TransactionCatalogReader(Connection connection) {
            this.connection = connection;
        }

        @Override
        public List<List<Object>> query(String sql, List<SqlParameter> parameters) {
            SqlTextGuard.check(sql);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                PostgresReadOnlyExecutor.bind(ps, parameters);
                try (ResultSet rs = ps.executeQuery()) {
                    int columns = rs.getMetaData().getColumnCount();
                    List<List<Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(columns);
                        for (int i = 1; i <= columns; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            } catch (SQLException e) {
                throw PostgresReadOnlyExecutor.map(e);
            }
        }
    }
}
