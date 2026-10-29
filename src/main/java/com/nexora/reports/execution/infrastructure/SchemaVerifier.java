package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.domain.NaturalKey.RelationName;
import com.nexora.reports.plan.domain.TypeCategory;
import com.nexora.reports.translation.domain.SchemaExpectation;
import com.nexora.reports.translation.domain.SchemaExpectation.ExpectedColumn;
import com.nexora.reports.translation.domain.SchemaExpectation.ExpectedPrimaryKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks the live PostgreSQL catalog against what the plan relies on, inside the execution transaction and with
 * parameterized catalog queries only. Any difference stops the execution ({@code PHYSICAL_SCHEMA_MISMATCH}); nothing
 * is ever corrected. It also refuses database roles that could write to the relations read.
 */
class SchemaVerifier {

    private static final Set<String> RELATION_KINDS = Set.of("r", "p", "v", "m");

    void verify(Connection connection, SchemaExpectation expectation) throws SQLException {
        Map<RelationName, Long> oids = new HashMap<>();
        for (RelationName relation : expectation.relations()) {
            oids.put(relation, relation(connection, relation));
        }
        for (ExpectedColumn column : expectation.columns()) {
            Long oid = oids.get(column.relation());
            if (oid == null) {
                oid = relation(connection, column.relation());
                oids.put(column.relation(), oid);
            }
            column(connection, oid, column);
        }
        for (ExpectedPrimaryKey key : expectation.primaryKeys()) {
            Long oid = oids.get(key.relation());
            if (oid == null) {
                throw mismatch("joined relation not checked");
            }
            primaryKey(connection, oid, key);
        }
    }

    private static long relation(Connection connection, RelationName relation) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT c.oid, c.relkind,
                       has_table_privilege(c.oid, 'INSERT') OR has_table_privilege(c.oid, 'UPDATE')
                       OR has_table_privilege(c.oid, 'DELETE') OR has_table_privilege(c.oid, 'TRUNCATE') AS can_write
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relname = ?""")) {
            ps.setString(1, relation.schema());
            ps.setString(2, relation.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw mismatch("relation missing");
                }
                long oid = rs.getLong(1);
                if (!RELATION_KINDS.contains(rs.getString(2))) {
                    throw mismatch("relation kind not supported");
                }
                if (rs.getBoolean(3)) {
                    throw new ExecutionException(ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY,
                            "database role has write privileges on a relation read by the report");
                }
                return oid;
            }
        }
    }

    private static void column(Connection connection, long relationOid, ExpectedColumn expected) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT t.typname, t.typtype
                  FROM pg_catalog.pg_attribute a
                  JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                 WHERE a.attrelid = ? AND a.attname = ? AND a.attnum > 0 AND NOT a.attisdropped""")) {
            ps.setLong(1, relationOid);
            ps.setString(2, expected.column());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw mismatch("column missing");
                }
                String type = rs.getString(1);
                if (!"b".equals(rs.getString(2)) || TypeCategory.of(type) != expected.category()
                        || !TypeCategory.executable(type)) {
                    throw mismatch("column type category changed");
                }
            }
        }
    }

    private static void primaryKey(Connection connection, long relationOid, ExpectedPrimaryKey expected)
            throws SQLException {
        Set<String> live = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT a.attname
                  FROM pg_catalog.pg_constraint k
                  JOIN pg_catalog.pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)
                 WHERE k.conrelid = ? AND k.contype = 'p'""")) {
            ps.setLong(1, relationOid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    live.add(rs.getString(1));
                }
            }
        }
        if (live.isEmpty() || !live.equals(new HashSet<>(expected.columns()))
                || live.size() != expected.columns().size()) {
            throw mismatch("joined side is no longer unique by exactly this primary key");
        }
    }

    private static ExecutionException mismatch(String reason) {
        return new ExecutionException(ErrorCode.PHYSICAL_SCHEMA_MISMATCH, reason);
    }
}
