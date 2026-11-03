package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.CatalogReader;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.plan.domain.NaturalKey.RelationName;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyRecord;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyStatus;
import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A4-B5: the tenancy check of one {@code /2} execution (P-1, P-1+), run by the executor inside its read-only
 * transaction, before the schema checks and before any data is read. Any doubt refuses with
 * {@code TENANT_SCOPE_UNVERIFIED}; nothing is read.
 * <ol>
 *   <li>an <b>approved, active</b> registration of a <b>dedicated</b> model ({@code SHARED_ROWS} never passes) for
 *       exactly the (tenant, data source) of the request and of the credentials, and credentials (host, port,
 *       database, user) equal to the registered ones;</li>
 *   <li>{@code sslmode=verify-full} in the credentials, so the driver has verified the server certificate and host
 *       name before the session exists, <b>and</b> {@code pg_stat_ssl} confirming that this session is encrypted.
 *       The second check is not a certificate check: it only proves encryption;</li>
 *   <li>database name and OID and the cluster's {@code system_identifier} equal to the registered ones. The system
 *       identifier identifies the cluster, not the database; together they detect another server, another database
 *       or a re-created database, but not every restore (a physical copy keeps them), which is why every change of
 *       the source is re-registered and re-approved;</li>
 *   <li>the role: the registered one, not a superuser and not a member (directly or through any chain, whatever its
 *       INHERIT setting) of a superuser or of {@link TenancyCatalog#FORBIDDEN_ROLES};</li>
 *   <li>the set of non-system schemas on which the role has effective USAGE (direct, inherited, PUBLIC) is
 *       <b>exactly</b> the registered set;</li>
 *   <li>every relation of the plan is in those schemas; a table must not have row security and all its partitions
 *       and inheriting tables must be in those schemas too; a view or materialized view is accepted only if
 *       everything it depends on can be proven to stay in scope (below). Any other kind of relation is refused.</li>
 * </ol>
 * <b>Views.</b> {@code pg_depend} records the static references of a view, not what a function does at run time.
 * So a view is accepted only when every dependency of its rewrite rule (recursively through other views) is a
 * relation of the registered schemas (tables as above), a type of {@code pg_catalog}, a collation or schema of
 * {@code pg_catalog} or of the registered schemas, or a function, operator or cast whose function is a built-in of
 * {@code pg_catalog} (language {@code internal}), not {@code SECURITY DEFINER}, and not one that runs SQL text or
 * reads large objects ({@link #UNPROVABLE_BUILT_INS}). Any other function, any other dependency class and anything
 * that cannot be resolved is refused. Because {@code pg_depend} never records built-in ("pinned") objects, the stored
 * query tree of the view ({@code pg_rewrite.ev_action}) is read as well and every relation, function and operator it
 * names is checked the same way; a view whose tree cannot be read is refused.
 */
public final class TenantScopeProbe {

    /** Built-in functions that execute SQL text, read large objects or change settings: their effect is unprovable. */
    static final Set<String> UNPROVABLE_BUILT_INS = Set.of("query_to_xml", "query_to_xmlschema",
            "query_to_xml_and_xmlschema", "cursor_to_xml", "cursor_to_xmlschema", "table_to_xml",
            "table_to_xmlschema", "table_to_xml_and_xmlschema", "schema_to_xml", "schema_to_xmlschema",
            "schema_to_xml_and_xmlschema", "database_to_xml", "database_to_xmlschema", "database_to_xml_and_xmlschema",
            "ts_stat", "ts_rewrite", "set_config", "loread", "lowrite");

    /** Fields of a stored query tree that name a relation, a function or an operator (PostgreSQL node output). */
    private static final Pattern TREE_REFERENCE = Pattern.compile(
            ":(relid|funcid|opfuncid|aggfnoid|winfnoid|hashfuncid|negfuncid|opno) (\\d+)");
    private static final Pattern TREE_OPERATOR_LIST = Pattern.compile(":opnos \\(o([ 0-9]*)\\)");

    private TenantScopeProbe() {
    }

    /**
     * The check of one execution, against the active registration of its (tenant, data source), or a check that
     * always refuses when there is none.
     */
    public static TenantScopeVerifier forExecution(Optional<TenancyRecord> active, ExecutionRequestV2 request) {
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(request, "request");
        return new RegisteredScope(active, request);
    }

    private static final class RegisteredScope implements TenantScopeVerifier {

        private final Optional<TenancyRecord> active;
        private final ExecutionRequestV2 request;

        RegisteredScope(Optional<TenancyRecord> active, ExecutionRequestV2 request) {
            this.active = active;
            this.request = request;
        }

        @Override
        public void verify(DataSourceCredentials credentials, CatalogReader reader) {
            TenancyRegistration registration = registration(credentials);
            DatabaseIdentity expected = registration.identity();
            TenancyCatalog catalog = new TenancyCatalog(reader);

            if (!"verify-full".equals(credentials.sslMode()) || !catalog.encrypted()) {
                throw refused("TLS with verify-full is required");
            }
            if (!catalog.canReadSystemIdentifier()) {
                throw refused("the cluster system identifier cannot be read");
            }
            TenancyCatalog.Identity identity = catalog.identity();
            if (!identity.databaseName().equals(expected.databaseName())
                    || identity.databaseOid() != expected.databaseOid()
                    || !identity.systemIdentifier().equals(expected.systemIdentifier())) {
                throw refused("database or cluster identity differs from the registration");
            }
            if (!identity.role().equals(expected.role())) {
                throw refused("role differs from the registration");
            }
            for (TenancyCatalog.Role role : catalog.roles()) {
                if (role.superuser() || TenancyCatalog.FORBIDDEN_ROLES.contains(role.name())) {
                    throw refused("role is, or is a member of, a role with access beyond its schemas");
                }
            }
            Set<String> schemas = new TreeSet<>(registration.schemas());
            if (!new TreeSet<>(catalog.usableSchemas()).equals(schemas)) {
                throw refused("the role can use schemas other than the registered ones");
            }
            for (RelationName relation : relations()) {
                if (!schemas.contains(relation.schema())) {
                    throw refused("plan reads a relation outside the registered schemas");
                }
                Optional<TenancyCatalog.Relation> live = catalog.relation(relation.schema(), relation.name());
                if (live.isPresent()) {
                    // A missing relation is reported by the schema check that follows (PHYSICAL_SCHEMA_MISMATCH).
                    checkRelation(catalog, live.get(), schemas, new HashSet<>());
                }
            }
        }

        private TenancyRegistration registration(DataSourceCredentials credentials) {
            if (active.isEmpty() || active.get().status() != TenancyStatus.ACTIVE) {
                throw refused("no approved tenancy registration");
            }
            TenancyRegistration r = active.get().registration();
            if (!r.model().executable()) {
                throw refused("tenancy model not supported");
            }
            if (!r.tenantId().equals(request.tenantId()) || !r.dataSourceId().equals(request.dataSourceId())
                    || !r.tenantId().equals(credentials.tenantId())
                    || !r.dataSourceId().equals(credentials.dataSourceId())) {
                throw refused("registration of another tenant or data source");
            }
            DatabaseIdentity identity = r.identity();
            if (!identity.host().equals(credentials.host()) || identity.port() != credentials.port()
                    || !identity.databaseName().equals(credentials.database())
                    || !identity.role().equals(credentials.username())) {
                throw refused("credentials differ from the registration");
            }
            return r;
        }

        private Set<RelationName> relations() {
            Set<RelationName> relations = new LinkedHashSet<>();
            for (SqlQueryV2 query : request.queries()) {
                relations.addAll(query.schema().relations());
            }
            return relations;
        }

        private static void checkRelation(TenancyCatalog catalog, TenancyCatalog.Relation relation,
                                          Set<String> schemas, Set<Long> seen) {
            if (!seen.add(relation.oid())) {
                return;
            }
            if (!schemas.contains(relation.schema())) {
                throw refused("relation outside the registered schemas");
            }
            switch (relation.kind()) {
                case "r", "p" -> checkTable(catalog, relation, schemas);
                case "v", "m" -> checkView(catalog, relation, schemas, seen);
                default -> throw refused("relation kind cannot be proven in scope");
            }
        }

        private static void checkTable(TenancyCatalog catalog, TenancyCatalog.Relation table, Set<String> schemas) {
            for (TenancyCatalog.Relation part : catalog.withDescendants(table.oid())) {
                if (!schemas.contains(part.schema())) {
                    throw refused("a partition or inheriting table is outside the registered schemas");
                }
                if (!"r".equals(part.kind()) && !"p".equals(part.kind())) {
                    throw refused("a partition or inheriting relation is not a table");
                }
                if (part.rowSecurity()) {
                    throw refused("row security policies cannot be proven in scope");
                }
            }
        }

        private static void checkView(TenancyCatalog catalog, TenancyCatalog.Relation view, Set<String> schemas,
                                      Set<Long> seen) {
            checkQueryTrees(catalog, view, schemas, seen);
            Deque<TenancyCatalog.Dependency> pending = new ArrayDeque<>(catalog.viewDependencies(view.oid()));
            while (!pending.isEmpty()) {
                TenancyCatalog.Dependency dependency = pending.pop();
                switch (dependency.catalogTable()) {
                    case "pg_class" -> checkRelation(catalog, catalog.relation(dependency.oid())
                            .orElseThrow(() -> refused("view dependency cannot be resolved")), schemas, seen);
                    case "pg_proc" -> checkFunction(catalog, dependency.oid());
                    case "pg_operator" -> checkFunction(catalog, catalog.operatorFunction(dependency.oid())
                            .orElseThrow(() -> refused("view dependency cannot be resolved")));
                    case "pg_cast" -> {
                        long function = catalog.castFunction(dependency.oid())
                                .orElseThrow(() -> refused("view dependency cannot be resolved"));
                        if (function != 0) {
                            checkFunction(catalog, function);
                        }
                    }
                    case "pg_type" -> {
                        if (!"pg_catalog".equals(schema(catalog, dependency))) {
                            throw refused("view uses a type outside pg_catalog");
                        }
                    }
                    case "pg_collation", "pg_namespace" -> {
                        String schema = schema(catalog, dependency);
                        if (!"pg_catalog".equals(schema) && !schemas.contains(schema)) {
                            throw refused("view depends on an object outside the registered schemas");
                        }
                    }
                    default -> throw refused("view dependency cannot be proven in scope");
                }
            }
        }

        /**
         * {@code pg_depend} does not record built-in ("pinned") objects, so a view calling {@code query_to_xml} or
         * reading a catalog table looks dependency-free there. The stored query tree names them: every relation,
         * function and operator it references is checked like a dependency. A view without a readable tree is refused.
         */
        private static void checkQueryTrees(TenancyCatalog catalog, TenancyCatalog.Relation view, Set<String> schemas,
                                            Set<Long> seen) {
            List<String> trees = catalog.viewQueryTrees(view.oid());
            if (trees.isEmpty() || trees.stream().anyMatch(t -> t == null || t.isBlank())) {
                throw refused("the query of a view cannot be read");
            }
            for (String tree : trees) {
                Matcher reference = TREE_REFERENCE.matcher(tree);
                while (reference.find()) {
                    long oid = Long.parseLong(reference.group(2));
                    if (oid == 0) {
                        continue;
                    }
                    switch (reference.group(1)) {
                        case "relid" -> {
                            if (oid != view.oid()) {
                                checkRelation(catalog, catalog.relation(oid)
                                        .orElseThrow(() -> refused("view relation cannot be resolved")), schemas, seen);
                            }
                        }
                        case "opno" -> checkFunction(catalog, catalog.operatorFunction(oid)
                                .orElseThrow(() -> refused("view operator cannot be resolved")));
                        default -> checkFunction(catalog, oid);
                    }
                }
                Matcher operators = TREE_OPERATOR_LIST.matcher(tree);
                while (operators.find()) {
                    for (String token : operators.group(1).trim().split(" +")) {
                        if (!token.isEmpty()) {
                            checkFunction(catalog, catalog.operatorFunction(Long.parseLong(token))
                                    .orElseThrow(() -> refused("view operator cannot be resolved")));
                        }
                    }
                }
            }
        }

        private static String schema(TenancyCatalog catalog, TenancyCatalog.Dependency dependency) {
            return catalog.schemaOf(dependency.catalogTable(), dependency.oid())
                    .orElseThrow(() -> refused("view dependency cannot be resolved"));
        }

        private static void checkFunction(TenancyCatalog catalog, long oid) {
            TenancyCatalog.Function function = catalog.function(oid)
                    .orElseThrow(() -> refused("view dependency cannot be resolved"));
            if (!"pg_catalog".equals(function.schema()) || function.securityDefiner()
                    || !"internal".equals(function.language()) || UNPROVABLE_BUILT_INS.contains(function.name())
                    || function.name().startsWith("lo_")) {
                throw refused("view calls a function whose effect cannot be proven in scope");
            }
        }
    }

    private static ExecutionException refused(String reason) {
        return new ExecutionException(ErrorCode.TENANT_SCOPE_UNVERIFIED, reason);
    }
}
