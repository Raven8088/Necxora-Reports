package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.ExecutionLimits;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.execution.domain.Requester.PrincipalType;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;
import com.nexora.reports.execution.infrastructure.ConnectionPools;
import com.nexora.reports.execution.infrastructure.ExecutorTestAccess;
import com.nexora.reports.execution.infrastructure.PostgresReadOnlyExecutor;
import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.plan2.domain.TestPlans;
import com.nexora.reports.support.TenantDatabase;
import com.nexora.reports.tenancy.InMemoryTenancyRegistry;
import com.nexora.reports.tenancy.application.TenancyRegistrationService;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyException;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRecord;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import com.nexora.reports.tenancy.domain.TenancyStatus;
import com.nexora.reports.translation2.domain.PlanV2Translator;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexora.reports.plan2.domain.TestPlans.list;
import static com.nexora.reports.plan2.domain.TestPlans.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A4-B5 (R4-11) against a real PostgreSQL with real TLS ({@link TenantDatabase}): registration captured from the
 * database, human approval, and the tenancy check of every execution through the A4-B4 executor. Every refusal is
 * {@code TENANT_SCOPE_UNVERIFIED} and happens <b>before any data query</b> (the executor's callback is never
 * reached). {@code SHARED_ROWS} is never accepted; nothing here is row-level security.
 */
class TenantScopeIntegrationTest {

    private static final ExecutionLimits LIMITS = new ExecutionLimits(100, 1000, Duration.ofSeconds(10),
            Duration.ofSeconds(2), Duration.ofSeconds(20), 100, Duration.ofSeconds(2));
    private static final RunBudget BUDGET = new RunBudget(Duration.ofSeconds(10), Duration.ofSeconds(2),
            Duration.ofSeconds(20), 100, Duration.ofSeconds(2), 1000, RunBudget.DEFAULT_MAX_ESTIMATED_BYTES);

    private static final UUID TENANT_A = UUID.fromString("b5000000-0000-4000-8000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("b5000000-0000-4000-8000-0000000000b1");
    private static final UUID SOURCE_A = UUID.fromString("b5000000-0000-4000-8000-0000000000a2");
    private static final UUID SOURCE_SA = UUID.fromString("b5000000-0000-4000-8000-0000000000a3");
    private static final UUID SOURCE_SB = UUID.fromString("b5000000-0000-4000-8000-0000000000b3");
    private static final UUID SOURCE_R = UUID.fromString("b5000000-0000-4000-8000-0000000000a4");

    private final List<ConnectionPools> pools = new ArrayList<>();
    private final Map<List<UUID>, DataSourceCredentials> configured = new HashMap<>();
    private final InMemoryTenancyRegistry registry = new InMemoryTenancyRegistry();
    private final PlanV2Translator translator = new PlanV2Translator();
    private final TenancyRegistrationService tenancy = new TenancyRegistrationService(registry,
            credentials -> new PostgresTenancyCapture(pools(LIMITS)).capture(credentials),
            (t, s) -> Optional.ofNullable(configured.get(List.of(t, s))),
            ContractFixtures.SHA256, Clock.systemUTC());

    TenantScopeIntegrationTest() {
        configure(TENANT_A, SOURCE_A, "tenencia_a", "lector_a");
        configure(TENANT_A, SOURCE_SA, "compartida", "lector_sa");
        configure(TENANT_B, SOURCE_SB, "compartida", "lector_sb");
    }

    @AfterEach
    void closePools() {
        pools.forEach(ConnectionPools::close);
    }

    // --- accepted: dedicated database and dedicated schemas ---

    @Test
    void anApprovedDedicatedDatabaseIsReadThroughVerifiedTls() {
        TenancyRegistration v1 = tenancy.register(ana(TENANT_A), SOURCE_A, TenancyModel.DEDICATED_DATABASE,
                List.of("public", "ventas"));
        tenancy.approve(luis(TENANT_A), v1.id());

        SnapshotRead read = run(TENANT_A, SOURCE_A, "ventas", configured(TENANT_A, SOURCE_A));

        assertThat(read.datasets().getFirst().rows()).containsExactly(List.of("Cliente A1", 1L),
                List.of("Cliente A2", 2L));
        assertThat(v1.identity().databaseName()).isEqualTo("tenencia_a");
        assertThat(v1.identity().role()).isEqualTo("lector_a");
        assertThat(v1.identity().systemIdentifier().matches("\\d+")).isTrue();
        assertThat(v1.schemas()).containsExactly("public", "ventas");
    }

    @Test
    void inASharedDatabaseEachTenantReadsOnlyItsOwnSchemas() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        approved(TENANT_B, SOURCE_SB, List.of("tenant_b"));

        SnapshotRead a = run(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA));
        SnapshotRead b = run(TENANT_B, SOURCE_SB, "tenant_b", configured(TENANT_B, SOURCE_SB));

        assertThat(a.datasets().getFirst().rows()).containsExactly(List.of("Solo de A", 1L));
        assertThat(b.datasets().getFirst().rows()).containsExactly(List.of("Solo de B", 1L), List.of("Otro de B", 2L));
    }

    @Test
    void aViewOverItsOwnTablesWithBuiltInFunctionsOnlyIsAccepted() {
        TenantDatabase.exec("compartida", "CREATE VIEW tenant_a.v_ok AS SELECT id, upper(cliente) AS cliente"
                + " FROM tenant_a.pedidos WHERE id > 0", "GRANT SELECT ON tenant_a.v_ok TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        SnapshotRead read = runCount(TENANT_A, SOURCE_SA, "tenant_a", "v_ok", configured(TENANT_A, SOURCE_SA));

        assertThat(read.datasets().getFirst().rows()).containsExactly(List.of("SOLO DE A", 1L));
    }

    // --- refused: registration ---

    @Test
    void withoutARegistrationNothingIsRead() {
        assertRefusedBeforeReading(() -> execute(TENANT_A, SOURCE_SA, "tenant_a", "pedidos",
                configured(TENANT_A, SOURCE_SA), Optional.empty()));
    }

    @Test
    void aPendingRegistrationIsNotEnough() {
        tenancy.register(ana(TENANT_A), SOURCE_SA, TenancyModel.DEDICATED_SCHEMAS, List.of("tenant_a"));

        assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA));
    }

    @Test
    void aRevokedRegistrationIsNoLongerUsed() {
        TenancyRegistration v1 = approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        tenancy.revoke(luis(TENANT_A), v1.id());

        assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA));
    }

    @Test
    void sharedRowsIsAlwaysRefusedEvenIfARecordClaimsToBeActive() {
        TenancyRegistration v1 = approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        TenancyRecord forged = new TenancyRecord(copy(v1, TenancyModel.SHARED_ROWS, v1.identity()), List.of(),
                TenancyStatus.ACTIVE);

        assertRefusedBeforeReading(() -> execute(TENANT_A, SOURCE_SA, "tenant_a", "pedidos",
                configured(TENANT_A, SOURCE_SA), Optional.of(forged)));
        assertThat(reason(() -> tenancy.register(ana(TENANT_A), SOURCE_SA, TenancyModel.SHARED_ROWS,
                List.of("tenant_a")))).isEqualTo(TenancyException.Reason.MODEL_NOT_SUPPORTED);
    }

    // --- refused: across tenants ---

    @Test
    void theRegistrationOfAnotherTenantNeverAuthorisesAnExecution() {
        approved(TENANT_B, SOURCE_SB, List.of("tenant_b"));

        assertRefusedBeforeReading(() -> execute(TENANT_A, SOURCE_SA, "tenant_a", "pedidos",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_B, SOURCE_SB)));
    }

    @Test
    void aPlanOfTenantAThatReadsATableOfTenantBIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefused(TENANT_A, SOURCE_SA, "tenant_b", configured(TENANT_A, SOURCE_SA));
    }

    @Test
    void credentialsConfiguredWithTheRoleOfAnotherTenantAreRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefused(TENANT_A, SOURCE_SA, "tenant_a", TenantDatabase.credentials(TENANT_A, SOURCE_SA, "compartida",
                "lector_sb"));
    }

    @Test
    void credentialsPointingToAnotherDatabaseOfTheSameServerAreRefused() {
        approved(TENANT_A, SOURCE_A, List.of("public", "ventas"));

        assertRefused(TENANT_A, SOURCE_A, "ventas", TenantDatabase.credentials(TENANT_A, SOURCE_A, "compartida",
                "lector_a"));
    }

    @Test
    void anotherRoleWithTheSamePrivilegesIsStillAnotherRole() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefused(TENANT_A, SOURCE_SA, "tenant_a", TenantDatabase.credentials(TENANT_A, SOURCE_SA, "compartida",
                "lector_sa2"));
    }

    // --- refused: identity of the server, the cluster and the database ---

    @Test
    void aRegistrationOfAnotherClusterIsRefused() {
        TenancyRegistration v1 = approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        DatabaseIdentity i = v1.identity();
        TenancyRecord otherCluster = new TenancyRecord(copy(v1, v1.model(), new DatabaseIdentity(i.host(), i.port(),
                i.databaseName(), i.databaseOid(), "1" + i.systemIdentifier(), i.role())), List.of(),
                TenancyStatus.ACTIVE);

        assertRefusedBeforeReading(() -> execute(TENANT_A, SOURCE_SA, "tenant_a", "pedidos",
                configured(TENANT_A, SOURCE_SA), Optional.of(otherCluster)));
    }

    @Test
    void aRecreatedDatabaseIsRefusedUntilANewVersionIsApprovedByAnotherPerson() {
        String db = "recreada";
        recreate(db);
        configure(TENANT_A, SOURCE_R, db, "lector_a");
        approved(TENANT_A, SOURCE_R, List.of("public", "ventas"));
        assertThat(run(TENANT_A, SOURCE_R, "ventas", configured(TENANT_A, SOURCE_R)).datasets().getFirst().rows())
                .hasSize(1);

        recreate(db);
        assertRefused(TENANT_A, SOURCE_R, "ventas", configured(TENANT_A, SOURCE_R));
        TenancyRegistration v2 = tenancy.register(ana(TENANT_A), SOURCE_R, TenancyModel.DEDICATED_DATABASE,
                List.of("public", "ventas"));
        assertRefused(TENANT_A, SOURCE_R, "ventas", configured(TENANT_A, SOURCE_R));
        assertThat(reason(() -> tenancy.approve(ana(TENANT_A), v2.id()))).isEqualTo(TenancyException.Reason.SAME_PERSON);
        tenancy.approve(luis(TENANT_A), v2.id());

        assertThat(v2.version()).isEqualTo(2);
        assertThat(run(TENANT_A, SOURCE_R, "ventas", configured(TENANT_A, SOURCE_R)).datasets().getFirst().rows())
                .hasSize(1);
    }

    @Test
    void anEncryptedSessionWithoutVerifyFullIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefused(TENANT_A, SOURCE_SA, "tenant_a", TenantDatabase.credentials(TENANT_A, SOURCE_SA, "compartida",
                "lector_sa", "require", null));
    }

    @Test
    void anUnencryptedSessionIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefused(TENANT_A, SOURCE_SA, "tenant_a", TenantDatabase.credentials(TENANT_A, SOURCE_SA, "compartida",
                "lector_sa", "disable", null));
    }

    @Test
    void verifyFullRefusesAServerItsCertificateAuthorityDoesNotVouchFor() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        AtomicInteger scopeChecks = new AtomicInteger();
        TenantScopeVerifier probe = TenantScopeProbe.forExecution(tenancy.active(TENANT_A, SOURCE_SA),
                request(TENANT_A, SOURCE_SA, "tenant_a", "pedidos"));
        AtomicInteger queries = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> ExecutorTestAccess.executeAll(executor(),
                TenantDatabase.credentials(TENANT_A, SOURCE_SA, "compartida", "lector_sa", "verify-full",
                        TenantDatabase.wrongCa()), request(TENANT_A, SOURCE_SA, "tenant_a", "pedidos"), BUDGET,
                (credentials, catalog) -> {
                    scopeChecks.incrementAndGet();
                    probe.verify(credentials, catalog);
                }, index -> queries.incrementAndGet()));

        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(((ExecutionException) thrown).code()).isEqualTo(ErrorCode.DATA_SOURCE_UNAVAILABLE);
        assertThat(scopeChecks.get()).isEqualTo(0);
        assertThat(queries.get()).isEqualTo(0);
    }

    // --- refused: effective privileges ---

    @Test
    void usageOnASchemaOfAnotherTenantGrantedDirectlyIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        whileGranted("GRANT USAGE ON SCHEMA tenant_b TO lector_sa", "REVOKE USAGE ON SCHEMA tenant_b FROM lector_sa",
                () -> assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA)));
    }

    @Test
    void usageOnASchemaOfAnotherTenantGrantedToPublicIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        whileGranted("GRANT USAGE ON SCHEMA tenant_b TO PUBLIC", "REVOKE USAGE ON SCHEMA tenant_b FROM PUBLIC",
                () -> assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA)));
    }

    @Test
    void usageOnASchemaOfAnotherTenantInheritedFromARoleIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        TenantDatabase.exec("compartida", "CREATE ROLE grupo_b NOLOGIN", "GRANT USAGE ON SCHEMA tenant_b TO grupo_b");
        try {
            whileGranted("GRANT grupo_b TO lector_sa", "REVOKE grupo_b FROM lector_sa",
                    () -> assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA)));
        } finally {
            TenantDatabase.exec("compartida", "REVOKE USAGE ON SCHEMA tenant_b FROM grupo_b", "DROP ROLE grupo_b");
        }
    }

    @Test
    void membershipOfARoleThatReadsAllDataIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        whileGranted("GRANT pg_read_all_data TO lector_sa", "REVOKE pg_read_all_data FROM lector_sa",
                () -> assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA)));
    }

    @Test
    void aPublicSchemaUsableButNotRegisteredIsRefused() {
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));
        whileGranted("GRANT USAGE ON SCHEMA public TO PUBLIC", "REVOKE USAGE ON SCHEMA public FROM PUBLIC",
                () -> assertRefused(TENANT_A, SOURCE_SA, "tenant_a", configured(TENANT_A, SOURCE_SA)));
    }

    // --- refused: what a relation can reach ---

    @Test
    void aViewOfTenantAThatReadsTenantBIsRefused() {
        TenantDatabase.exec("compartida", "CREATE VIEW tenant_a.v_fuga AS SELECT id, cliente FROM tenant_b.pedidos",
                "GRANT SELECT ON tenant_a.v_fuga TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefusedBeforeReading(() -> executeCount(TENANT_A, SOURCE_SA, "tenant_a", "v_fuga",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_A, SOURCE_SA)));
    }

    @Test
    void aViewThatCallsASecurityDefinerFunctionIsRefused() {
        TenantDatabase.exec("compartida", "CREATE FUNCTION tenant_a.f_definer(t text) RETURNS text"
                        + " LANGUAGE sql SECURITY DEFINER AS 'SELECT t'",
                "CREATE VIEW tenant_a.v_definer AS SELECT id, tenant_a.f_definer(cliente) AS cliente"
                        + " FROM tenant_a.pedidos",
                "GRANT SELECT ON tenant_a.v_definer TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefusedBeforeReading(() -> executeCount(TENANT_A, SOURCE_SA, "tenant_a", "v_definer",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_A, SOURCE_SA)));
    }

    @Test
    void aViewThatCallsAUserFunctionIsRefusedBecauseItsBodyCannotBeProven() {
        TenantDatabase.exec("compartida", "CREATE FUNCTION tenant_a.f_invoker(t text) RETURNS text"
                        + " LANGUAGE plpgsql AS 'BEGIN RETURN t; END'",
                "CREATE VIEW tenant_a.v_invoker AS SELECT id, tenant_a.f_invoker(cliente) AS cliente"
                        + " FROM tenant_a.pedidos",
                "GRANT SELECT ON tenant_a.v_invoker TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefusedBeforeReading(() -> executeCount(TENANT_A, SOURCE_SA, "tenant_a", "v_invoker",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_A, SOURCE_SA)));
    }

    @Test
    void aViewThatRunsSqlTextThroughABuiltInIsRefused() {
        TenantDatabase.exec("compartida", "CREATE VIEW tenant_a.v_dinamica AS SELECT id,"
                        + " query_to_xml('SELECT 1', false, false, '')::text AS cliente FROM tenant_a.pedidos",
                "GRANT SELECT ON tenant_a.v_dinamica TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefusedBeforeReading(() -> executeCount(TENANT_A, SOURCE_SA, "tenant_a", "v_dinamica",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_A, SOURCE_SA)));
    }

    @Test
    void aTableWithAnInheritingTableOfAnotherTenantIsRefused() {
        TenantDatabase.exec("compartida", "CREATE TABLE tenant_a.padre (id integer PRIMARY KEY, cliente text)",
                "CREATE TABLE tenant_b.hija () INHERITS (tenant_a.padre)",
                "INSERT INTO tenant_b.hija VALUES (9, 'Fila de B')",
                "GRANT SELECT ON tenant_a.padre TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefusedBeforeReading(() -> execute(TENANT_A, SOURCE_SA, "tenant_a", "padre",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_A, SOURCE_SA)));
    }

    @Test
    void aTableWithRowSecurityIsRefusedBecauseItsPoliciesCannotBeProven() {
        TenantDatabase.exec("compartida", "CREATE TABLE tenant_a.con_rls (id integer PRIMARY KEY, cliente text)",
                "ALTER TABLE tenant_a.con_rls ENABLE ROW LEVEL SECURITY",
                "GRANT SELECT ON tenant_a.con_rls TO lector_sa");
        approved(TENANT_A, SOURCE_SA, List.of("tenant_a"));

        assertRefusedBeforeReading(() -> execute(TENANT_A, SOURCE_SA, "tenant_a", "con_rls",
                configured(TENANT_A, SOURCE_SA), tenancy.active(TENANT_A, SOURCE_SA)));
    }

    // --- registration against the real database ---

    @Test
    void registrationRefusesSchemasTheRoleCannotUseAndConnectionsWithoutVerifyFull() {
        assertThat(reason(() -> tenancy.register(ana(TENANT_A), SOURCE_SA, TenancyModel.DEDICATED_SCHEMAS,
                List.of("tenant_a", "tenant_b")))).isEqualTo(TenancyException.Reason.CAPTURE_MISMATCH);
        configured.put(List.of(TENANT_A, SOURCE_SA), TenantDatabase.credentials(TENANT_A, SOURCE_SA, "compartida",
                "lector_sa", "require", null));
        assertThat(reason(() -> tenancy.register(ana(TENANT_A), SOURCE_SA, TenancyModel.DEDICATED_SCHEMAS,
                List.of("tenant_a")))).isEqualTo(TenancyException.Reason.TLS_REQUIRED);
    }

    // ------------------------------------------------------------------ helpers

    private ConnectionPools pools(ExecutionLimits limits) {
        ConnectionPools pool = new ConnectionPools(limits);
        pools.add(pool);
        return pool;
    }

    private PostgresReadOnlyExecutor executor() {
        return new PostgresReadOnlyExecutor(pools(LIMITS), 16);
    }

    private void configure(UUID tenant, UUID source, String database, String user) {
        configured.put(List.of(tenant, source), TenantDatabase.credentials(tenant, source, database, user));
    }

    private DataSourceCredentials configured(UUID tenant, UUID source) {
        return configured.get(List.of(tenant, source));
    }

    private TenancyRegistration approved(UUID tenant, UUID source, List<String> schemas) {
        TenancyRegistration registration = tenancy.register(ana(tenant), source, TenancyModel.DEDICATED_SCHEMAS,
                schemas);
        tenancy.approve(luis(tenant), registration.id());
        return registration;
    }

    private static Requester ana(UUID tenant) {
        return new Requester("ana", PrincipalType.HUMAN, tenant);
    }

    private static Requester luis(UUID tenant) {
        return new Requester("luis", PrincipalType.HUMAN, tenant);
    }

    private ExecutionRequestV2 request(UUID tenant, UUID source, String schema, String table) {
        SqlQueryV2 q = translator.translate(TestPlans.validated(TestPlans.oneColumn(schema, table, "cliente", "TEXT",
                "TEXT", "IS_NOT_NULL", list())), 10);
        return new ExecutionRequestV2(tenant, source, List.of(q));
    }

    private ExecutionRequestV2 countRequest(UUID tenant, UUID source, String schema, String view) {
        SqlQueryV2 q = translator.translate(TestPlans.validated(countByClient(schema, view)), 10);
        return new ExecutionRequestV2(tenant, source, List.of(q));
    }

    private SnapshotRead run(UUID tenant, UUID source, String schema, DataSourceCredentials credentials) {
        return execute(tenant, source, schema, "pedidos", credentials, tenancy.active(tenant, source));
    }

    private SnapshotRead runCount(UUID tenant, UUID source, String schema, String view,
                                  DataSourceCredentials credentials) {
        return executeCount(tenant, source, schema, view, credentials, tenancy.active(tenant, source));
    }

    private SnapshotRead execute(UUID tenant, UUID source, String schema, String table,
                                 DataSourceCredentials credentials, Optional<TenancyRecord> active) {
        ExecutionRequestV2 request = request(tenant, source, schema, table);
        return ExecutorTestAccess.executeAll(executor(), credentials, request, BUDGET,
                TenantScopeProbe.forExecution(active, request), index -> queries.incrementAndGet());
    }

    private SnapshotRead executeCount(UUID tenant, UUID source, String schema, String view,
                                      DataSourceCredentials credentials, Optional<TenancyRecord> active) {
        ExecutionRequestV2 request = countRequest(tenant, source, schema, view);
        return ExecutorTestAccess.executeAll(executor(), credentials, request, BUDGET,
                TenantScopeProbe.forExecution(active, request), index -> queries.incrementAndGet());
    }

    private final AtomicInteger queries = new AtomicInteger();

    private void assertRefused(UUID tenant, UUID source, String schema, DataSourceCredentials credentials) {
        assertRefusedBeforeReading(() -> run(tenant, source, schema, credentials));
    }

    private void assertRefusedBeforeReading(Runnable call) {
        int before = queries.get();
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(((ExecutionException) thrown).code()).isEqualTo(ErrorCode.TENANT_SCOPE_UNVERIFIED);
        assertThat(queries.get()).isEqualTo(before);
    }

    private static void whileGranted(String grant, String revoke, Runnable check) {
        TenantDatabase.exec("compartida", grant);
        try {
            check.run();
        } finally {
            TenantDatabase.exec("compartida", revoke);
        }
    }

    private static void recreate(String db) {
        TenantDatabase.exec("nexora_tenancy", "DROP DATABASE IF EXISTS " + db + " WITH (FORCE)",
                "CREATE DATABASE " + db);
        TenantDatabase.exec(db, "CREATE SCHEMA ventas",
                "CREATE TABLE ventas.pedidos (id integer PRIMARY KEY, cliente text)",
                "INSERT INTO ventas.pedidos VALUES (1, 'Cliente R1')",
                "GRANT USAGE ON SCHEMA ventas TO lector_a", "GRANT SELECT ON ventas.pedidos TO lector_a");
    }

    private static TenancyRegistration copy(TenancyRegistration r, TenancyModel model, DatabaseIdentity identity) {
        return new TenancyRegistration(r.id(), r.tenantId(), r.dataSourceId(), r.version(), model, identity,
                r.schemas(), r.registeredBy(), r.registeredAt(), r.fingerprint());
    }

    private static TenancyException.Reason reason(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(TenancyException.class);
        return ((TenancyException) thrown).reason();
    }

    /** Count of rows by client over one relation (a view has no primary key: the order's tie-breaker is the group). */
    private static Map<String, Object> countByClient(String schema, String relation) {
        String rel = "d0000000-0000-4000-8000-000000000001";
        String col = "d0000000-0000-4000-8000-000000000002";
        Map<String, Object> plan = map("format", "nexora-logical-plan/2",
                "tenantId", UUID.nameUUIDFromBytes("tenant".getBytes()).toString(),
                "dataSourceId", UUID.nameUUIDFromBytes("source".getBytes()).toString(),
                "metadataSnapshotId", UUID.nameUUIDFromBytes("snapshot".getBytes()).toString(),
                "specFingerprint", "a".repeat(64), "datasetId", "por_cliente");
        plan.put("definitions", list());
        plan.put("parameters", list());
        plan.put("objects", list(
                map("id", rel, "type", "VIEW", "naturalKey", map("schema", schema, "name", relation),
                        "uniqueKeys", list()),
                map("id", col, "type", "COLUMN", "naturalKey", map("schema", schema, "name", relation,
                        "column", "cliente"), "parentId", rel, "typeCategory", "TEXT", "nullable", true)));
        plan.put("relationships", list());
        plan.put("operations", list(
                map("op", "SCAN", "alias", "v", "object", rel),
                map("op", "AGGREGATE", "groupBy", list(map("output", "cliente", "column",
                                map("alias", "v", "column", col))),
                        "measures", list(map("output", "filas", "aggregation", "COUNT"))),
                map("op", "ORDER", "keys", list(map("output", "cliente", "direction", "ASC", "nulls", "LAST",
                        "collation", "C")), "tieBreaker", map("kind", "GROUP_KEYS", "outputs", list("cliente"))),
                map("op", "LIMIT", "maxRows", 10L, "detectTruncation", true)));
        plan.put("output", map("columns", list(
                map("output", "cliente", "label", "Cliente", "valueType", "TEXT", "role", "DIMENSION",
                        "origin", "INFERRED"),
                map("output", "filas", "label", "Filas", "valueType", "INTEGER", "role", "MEASURE",
                        "origin", "INFERRED"))));
        plan.put("fingerprint", "b".repeat(64));
        return plan;
    }
}
