package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.execution.application.port.CatalogReader;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog queries of the tenancy checks (A4-B5), shared by the registration capture and the execution probe so that
 * both read exactly the same facts. Parameterized only, no literal in the text (they go through
 * {@code SqlTextGuard}); every query runs in the read-only transaction of its caller.
 */
final class TenancyCatalog {

    /** Predefined roles whose membership gives access beyond any schema grant, or to the server itself. */
    static final Set<String> FORBIDDEN_ROLES = Set.of("pg_read_all_data", "pg_write_all_data", "pg_read_server_files",
            "pg_write_server_files", "pg_execute_server_program");

    private final CatalogReader catalog;

    TenancyCatalog(CatalogReader catalog) {
        this.catalog = catalog;
    }

    record Identity(String databaseName, long databaseOid, String systemIdentifier, String role) {
    }

    record Role(String name, boolean superuser) {
    }

    record Relation(long oid, String schema, String name, String kind, boolean rowSecurity) {
    }

    record Dependency(String catalogTable, long oid) {
    }

    record Function(String schema, String name, boolean securityDefiner, String language) {
    }

    /** Whether this role may read the cluster's system identifier at all. */
    boolean canReadSystemIdentifier() {
        return bool(one("SELECT pg_catalog.has_function_privilege(?, ?)",
                text("pg_catalog.pg_control_system()"), text("EXECUTE")));
    }

    /** Database name and OID, cluster system identifier (identifies the cluster, not the database) and role. */
    Identity identity() {
        List<Object> row = row("""
                SELECT pg_catalog.current_database()::text, d.oid::bigint, s.system_identifier::text,
                       CURRENT_USER::text
                  FROM pg_catalog.pg_database d, pg_catalog.pg_control_system() s
                 WHERE d.datname = pg_catalog.current_database()""");
        return new Identity((String) row.get(0), (Long) row.get(1), (String) row.get(2), (String) row.get(3));
    }

    /**
     * Whether this session is encrypted. This is not the certificate check: the identity of the server certificate
     * is verified by the driver with {@code sslmode=verify-full} before the session exists.
     */
    boolean encrypted() {
        List<List<Object>> rows = catalog.query(
                "SELECT s.ssl FROM pg_catalog.pg_stat_ssl s WHERE s.pid = pg_catalog.pg_backend_pid()", List.of());
        return rows.size() == 1 && Boolean.TRUE.equals(rows.getFirst().getFirst());
    }

    /**
     * Non-system schemas on which the current role has effective USAGE: {@code has_schema_privilege} counts direct
     * grants, grants to roles it inherits and grants to PUBLIC.
     */
    List<String> usableSchemas() {
        List<String> schemas = new ArrayList<>();
        for (List<Object> row : catalog.query("""
                SELECT n.nspname::text FROM pg_catalog.pg_namespace n
                 WHERE pg_catalog.has_schema_privilege(n.oid, ?)
                   AND n.nspname <> ? AND n.nspname <> ? AND n.nspname NOT LIKE ? AND n.nspname NOT LIKE ?
                 ORDER BY 1""", List.of(text("USAGE"), text("pg_catalog"), text("information_schema"),
                text("pg\\_toast%"), text("pg\\_temp\\_%")))) {
            schemas.add((String) row.getFirst());
        }
        return schemas;
    }

    /** The current role and every role it is a member of, recursively, whatever their INHERIT setting. */
    List<Role> roles() {
        List<Role> roles = new ArrayList<>();
        for (List<Object> row : catalog.query("""
                WITH RECURSIVE m(roleid) AS (
                    SELECT r.oid FROM pg_catalog.pg_roles r WHERE r.rolname = CURRENT_USER
                    UNION
                    SELECT a.roleid FROM pg_catalog.pg_auth_members a JOIN m ON a.member = m.roleid)
                SELECT r.rolname::text, r.rolsuper FROM m JOIN pg_catalog.pg_roles r ON r.oid = m.roleid""",
                List.of())) {
            roles.add(new Role((String) row.get(0), (Boolean) row.get(1)));
        }
        return roles;
    }

    Optional<Relation> relation(String schema, String name) {
        return relations("""
                SELECT c.oid::bigint, n.nspname::text, c.relname::text, c.relkind::text, c.relrowsecurity
                  FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relname = ?""", List.of(text(schema), text(name))).stream().findFirst();
    }

    Optional<Relation> relation(long oid) {
        return relations("""
                SELECT c.oid::bigint, n.nspname::text, c.relname::text, c.relkind::text, c.relrowsecurity
                  FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.oid = CAST(CAST(? AS bigint) AS oid)""", List.of(bigint(oid))).stream().findFirst();
    }

    /** The relation and every table that inherits from it or is one of its partitions, recursively. */
    List<Relation> withDescendants(long oid) {
        return relations("""
                WITH RECURSIVE d(oid) AS (
                    SELECT CAST(CAST(? AS bigint) AS oid)
                    UNION
                    SELECT i.inhrelid FROM pg_catalog.pg_inherits i JOIN d ON i.inhparent = d.oid)
                SELECT c.oid::bigint, n.nspname::text, c.relname::text, c.relkind::text, c.relrowsecurity
                  FROM d JOIN pg_catalog.pg_class c ON c.oid = d.oid
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace""", List.of(bigint(oid)));
    }

    /**
     * The objects the rewrite rule of a view or materialized view depends on, as recorded in {@code pg_depend}.
     * This records static references only: what a function does at run time is not in it.
     */
    List<Dependency> viewDependencies(long viewOid) {
        List<Dependency> dependencies = new ArrayList<>();
        for (List<Object> row : catalog.query("""
                SELECT k.relname::text, d.refobjid::bigint
                  FROM pg_catalog.pg_rewrite r
                  JOIN pg_catalog.pg_depend d ON d.objid = r.oid
                  JOIN pg_catalog.pg_class k ON k.oid = d.refclassid
                  JOIN pg_catalog.pg_class own ON own.oid = d.classid
                 WHERE r.ev_class = CAST(CAST(? AS bigint) AS oid) AND own.relname = ?
                   AND NOT (k.relname = ? AND d.refobjid = r.ev_class)""",
                List.of(bigint(viewOid), text("pg_rewrite"), text("pg_class")))) {
            dependencies.add(new Dependency((String) row.get(0), (Long) row.get(1)));
        }
        return dependencies;
    }

    /**
     * The stored query trees ({@code pg_rewrite.ev_action}) of a view or materialized view, as text. Unlike
     * {@code pg_depend}, they also name built-in ("pinned") functions, operators and catalog relations, which
     * {@code pg_depend} never records.
     */
    List<String> viewQueryTrees(long viewOid) {
        List<String> trees = new ArrayList<>();
        for (List<Object> row : catalog.query("SELECT r.ev_action::text FROM pg_catalog.pg_rewrite r"
                + " WHERE r.ev_class = CAST(CAST(? AS bigint) AS oid)", List.of(bigint(viewOid)))) {
            trees.add((String) row.getFirst());
        }
        return trees;
    }

    Optional<Function> function(long oid) {
        List<List<Object>> rows = catalog.query("""
                SELECT n.nspname::text, p.proname::text, p.prosecdef, l.lanname::text
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                  JOIN pg_catalog.pg_language l ON l.oid = p.prolang
                 WHERE p.oid = CAST(CAST(? AS bigint) AS oid)""", List.of(bigint(oid)));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Object> row = rows.getFirst();
        return Optional.of(new Function((String) row.get(0), (String) row.get(1), (Boolean) row.get(2),
                (String) row.get(3)));
    }

    /** The function implementing an operator, or empty if the operator does not exist. */
    Optional<Long> operatorFunction(long oid) {
        return oidOf("SELECT o.oprcode::oid::bigint FROM pg_catalog.pg_operator o"
                + " WHERE o.oid = CAST(CAST(? AS bigint) AS oid)", oid);
    }

    /** The function of a cast (0 for a binary-coercible cast), or empty if the cast does not exist. */
    Optional<Long> castFunction(long oid) {
        return oidOf("SELECT c.castfunc::bigint FROM pg_catalog.pg_cast c"
                + " WHERE c.oid = CAST(CAST(? AS bigint) AS oid)", oid);
    }

    /** Schema of a type, a collation or a schema itself, or empty if it does not exist. */
    Optional<String> schemaOf(String catalogTable, long oid) {
        String sql = switch (catalogTable) {
            case "pg_type" -> "SELECT n.nspname::text FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n"
                    + " ON n.oid = t.typnamespace WHERE t.oid = CAST(CAST(? AS bigint) AS oid)";
            case "pg_collation" -> "SELECT n.nspname::text FROM pg_catalog.pg_collation c JOIN pg_catalog.pg_namespace n"
                    + " ON n.oid = c.collnamespace WHERE c.oid = CAST(CAST(? AS bigint) AS oid)";
            case "pg_namespace" -> "SELECT n.nspname::text FROM pg_catalog.pg_namespace n"
                    + " WHERE n.oid = CAST(CAST(? AS bigint) AS oid)";
            default -> throw new IllegalArgumentException("unsupported catalog");
        };
        List<List<Object>> rows = catalog.query(sql, List.of(bigint(oid)));
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.getFirst().getFirst());
    }

    // ------------------------------------------------------------------ helpers

    private List<Relation> relations(String sql, List<SqlParameter> parameters) {
        List<Relation> relations = new ArrayList<>();
        for (List<Object> row : catalog.query(sql, parameters)) {
            relations.add(new Relation((Long) row.get(0), (String) row.get(1), (String) row.get(2),
                    (String) row.get(3), (Boolean) row.get(4)));
        }
        return relations;
    }

    private Optional<Long> oidOf(String sql, long oid) {
        List<List<Object>> rows = catalog.query(sql, List.of(bigint(oid)));
        return rows.isEmpty() ? Optional.empty() : Optional.of((Long) rows.getFirst().getFirst());
    }

    private List<Object> row(String sql) {
        List<List<Object>> rows = catalog.query(sql, List.of());
        if (rows.size() != 1) {
            throw new IllegalStateException("expected one row");
        }
        return rows.getFirst();
    }

    private Object one(String sql, SqlParameter... parameters) {
        List<List<Object>> rows = catalog.query(sql, List.of(parameters));
        return rows.isEmpty() ? null : rows.getFirst().getFirst();
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static SqlParameter text(String value) {
        return new SqlParameter(SqlType.TEXT, value);
    }

    private static SqlParameter bigint(long value) {
        return new SqlParameter(SqlType.BIGINT, value);
    }
}
