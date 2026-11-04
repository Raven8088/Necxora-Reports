package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.credentials.domain.TransportException;
import com.nexora.reports.support.TestDatabase;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B5: the tenancy registry in Reports' own database, migrated by Flyway (V1 and V2) in a database of its own in
 * the R1 test container, used through the restricted application role. The database itself keeps the registry
 * append-only, refuses {@code SHARED_ROWS} and an approval by the registrant, and every read is scoped by tenant.
 */
class JdbcTenancyRegistryIntegrationTest {

    private static final String DATABASE = "tenancy_registry";
    private static final UUID TENANT = UUID.fromString("b5000000-0000-4000-8000-000000000021");
    private static final UUID SOURCE = UUID.fromString("b5000000-0000-4000-8000-000000000022");
    private static final DatabaseIdentity IDENTITY = new DatabaseIdentity("db.example", 5432, "erp", 16384,
            "7312345678901234567", "reader");

    private static JdbcTenancyRegistry registry;

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + DATABASE);
        }
        Flyway.configure().dataSource(url(), TestDatabase.superuserName(), TestDatabase.superuserPassword())
                .schemas("reports").defaultSchema("reports")
                .placeholders(Map.of("appRole", TestDatabase.APP_ROLE)).locations("classpath:db/migration")
                .load().migrate();
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(url());
        app.setUser(TestDatabase.APP_ROLE);
        app.setPassword(TestDatabase.APP_PASSWORD);
        registry = new JdbcTenancyRegistry(app);
    }

    @Test
    void aVersionAndItsDecisionsAreStoredAndReadBackExactly() {
        TenancyRegistration v1 = registration(TENANT, 1, "ana");
        registry.insert(v1);
        TenancyDecision approval = decision(v1, TenancyDecision.Kind.APPROVED, "luis");
        registry.insert(approval);

        assertThat(registry.registrations(TENANT, SOURCE)).containsExactly(v1);
        assertThat(registry.registration(TENANT, v1.id()).orElseThrow()).isEqualTo(v1);
        assertThat(registry.decisions(TENANT, SOURCE)).containsExactly(approval);
    }

    @Test
    void readsAreScopedByTenant() {
        UUID tenant = UUID.randomUUID();
        TenancyRegistration v1 = registration(tenant, 1, "ana");
        registry.insert(v1);

        assertThat(registry.registration(UUID.randomUUID(), v1.id())).isEmpty();
        assertThat(registry.registrations(UUID.randomUUID(), SOURCE)).isEmpty();
        assertThat(registry.decisions(UUID.randomUUID(), SOURCE)).isEmpty();
    }

    @Test
    void aVersionNumberExistsOncePerSource() {
        UUID tenant = UUID.randomUUID();
        registry.insert(registration(tenant, 1, "ana"));

        assertThatThrownBy(() -> registry.insert(registration(tenant, 1, "eva")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDatabaseRefusesAnApprovalByTheRegistrant() {
        TenancyRegistration v1 = registration(UUID.randomUUID(), 1, "ana");
        registry.insert(v1);

        assertThatThrownBy(() -> registry.insert(decision(v1, TenancyDecision.Kind.APPROVED, "ana")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDecisionOfEachKindIsTakenOnce() {
        TenancyRegistration v1 = registration(UUID.randomUUID(), 1, "ana");
        registry.insert(v1);
        registry.insert(decision(v1, TenancyDecision.Kind.APPROVED, "luis"));

        assertThatThrownBy(() -> registry.insert(decision(v1, TenancyDecision.Kind.APPROVED, "eva")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDatabaseRefusesSharedRows() {
        TenancyRegistration shared = new TenancyRegistration(UUID.randomUUID(), UUID.randomUUID(), SOURCE, 1,
                TenancyModel.SHARED_ROWS, IDENTITY, List.of("public"), "ana", now(), "f".repeat(64));

        assertThatThrownBy(() -> registry.insert(shared)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theApplicationRoleCanOnlyInsertAndRead() throws SQLException {
        TenancyRegistration v1 = registration(UUID.randomUUID(), 1, "ana");
        registry.insert(v1);

        for (String sql : List.of("UPDATE tenancy_registration SET version = 9",
                "DELETE FROM tenancy_registration", "DELETE FROM tenancy_decision")) {
            assertThatThrownBy(() -> app(sql)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void evenTheOwnerCannotUpdateDeleteOrTruncateTheRegistry() throws SQLException {
        TenancyRegistration v1 = registration(UUID.randomUUID(), 1, "ana");
        registry.insert(v1);
        registry.insert(decision(v1, TenancyDecision.Kind.APPROVED, "luis"));

        for (String sql : List.of("UPDATE reports.tenancy_registration SET version = 9",
                "DELETE FROM reports.tenancy_registration", "TRUNCATE reports.tenancy_registration CASCADE",
                "UPDATE reports.tenancy_decision SET decided_by = 'x'", "DELETE FROM reports.tenancy_decision",
                "TRUNCATE reports.tenancy_decision")) {
            assertThatThrownBy(() -> owner(sql)).isInstanceOf(SQLException.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String url() {
        return "jdbc:postgresql://" + TestDatabase.host() + ":" + TestDatabase.port() + "/" + DATABASE
                + "?currentSchema=reports";
    }

    private static void app(String sql) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(url(), TestDatabase.APP_ROLE,
                TestDatabase.APP_PASSWORD); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void owner(String sql) throws SQLException {
        try (Connection c = TestDatabase.superuser(DATABASE); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void aVersionCapturedUnderATransportExceptionKeepsItExactly() {
        TransportException ex = new TransportException("EX-TLS-1", Instant.parse("2026-11-17T23:59:59Z"),
                "a".repeat(64));
        TenancyRegistration v1 = new TenancyRegistration(UUID.randomUUID(), UUID.randomUUID(), SOURCE, 1,
                TenancyModel.DEDICATED_SCHEMAS, IDENTITY, List.of("public"), "ana", now(), "f".repeat(64), ex);
        registry.insert(v1);

        TenancyRegistration read = registry.registration(v1.tenantId(), v1.id()).orElseThrow();
        assertThat(read).isEqualTo(v1);
        assertThat(read.transportException()).isEqualTo(ex);
    }

    @Test
    void aVersionWithoutTransportExceptionReadsBackWithoutOne() {
        TenancyRegistration v1 = registration(UUID.randomUUID(), 1, "ana");
        registry.insert(v1);

        assertThat(registry.registration(v1.tenantId(), v1.id()).orElseThrow().transportException()).isNull();
    }

    @Test
    void theDatabaseRefusesAPartialOrMalformedTransportException() throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(url(), TestDatabase.superuserName(),
                TestDatabase.superuserPassword()); Statement s = c.createStatement()) {
            String base = "INSERT INTO reports.tenancy_registration (id, tenant_id, data_source_id, version, model,"
                    + " host, port, database_name, database_oid, system_identifier, role_name, schemas, registered_by,"
                    + " registered_at, fingerprint, transport_exception_id, transport_exception_expires_at,"
                    + " transport_exception_binding) VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
                    + " 1, 'DEDICATED_SCHEMAS', 'h', 1, 'd', 1, '1', 'r', ARRAY['public'], 'ana', now(), '"
                    + "f".repeat(64) + "', ";
            for (String values : List.of("'EX-TLS-1', NULL, NULL)", "NULL, now(), NULL)",
                    "NULL, NULL, '" + "a".repeat(64) + "')", "'ex-tls-1', now(), '" + "a".repeat(64) + "')",
                    "'EX-TLS-1', now(), '" + "A".repeat(64) + "')")) {
                assertThatThrownBy(() -> s.execute(base + values)).as(values).isInstanceOf(SQLException.class);
            }
        }
    }

    /** The same INSERT for every case below, so a refusal can only come from the transport exception columns. */
    private static String insertWithTransportException(String values) {
        return "INSERT INTO reports.tenancy_registration (id, tenant_id, data_source_id, version, model, host, port,"
                + " database_name, database_oid, system_identifier, role_name, schemas, registered_by, registered_at,"
                + " fingerprint, transport_exception_id, transport_exception_expires_at, transport_exception_binding)"
                + " VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, 'DEDICATED_SCHEMAS', 'h', 1,"
                + " 'd', 1, '1', 'r', ARRAY['public'], 'ana', now(), '" + "f".repeat(64) + "', " + values + ")";
    }

    private static Connection superuser() throws SQLException {
        return java.sql.DriverManager.getConnection(url(), TestDatabase.superuserName(),
                TestDatabase.superuserPassword());
    }

    static List<String> partialOrMalformedTransportExceptions() {
        String binding = "'" + "a".repeat(64) + "'";
        return List.of(
                // partial: every combination of one or two of the three columns
                "'EX-TLS-1', NULL, NULL", "NULL, now(), NULL", "NULL, NULL, " + binding,
                "'EX-TLS-1', now(), NULL", "'EX-TLS-1', NULL, " + binding, "NULL, now(), " + binding,
                // complete but malformed
                "'ex-tls-1', now(), " + binding, "'-EX', now(), " + binding,
                "'EX-TLS-1', now(), '" + "A".repeat(64) + "'", "'EX-TLS-1', now(), '" + "a".repeat(63) + "'",
                "'EX-TLS-1', now(), '" + "g".repeat(64) + "'");
    }

    @ParameterizedTest
    @MethodSource("partialOrMalformedTransportExceptions")
    void eachPartialOrMalformedTransportExceptionViolatesTheConstraint(String values) throws SQLException {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute(insertWithTransportException(values)))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23514");
                        assertThat(e.getMessage()).contains("tenancy_registration_transport_ck");
                    });
        }
    }

    @Test
    void anAbsentOrCompleteTransportExceptionIsAcceptedByTheSameStatement() throws SQLException {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate(insertWithTransportException("NULL, NULL, NULL"))).isEqualTo(1);
            assertThat(s.executeUpdate(insertWithTransportException("'EX-TLS-1', now(), '" + "a".repeat(64) + "'")))
                    .isEqualTo(1);
        }
    }

    @Test
    void v6ReplacedTheConstraintOfV5() throws SQLException {
        try (Connection c = superuser(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT count(*), min(pg_get_constraintdef(oid)) FROM pg_constraint"
                        + " WHERE conname = 'tenancy_registration_transport_ck'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).contains("transport_exception_binding IS NOT NULL");
        }
    }

    private static TenancyRegistration registration(UUID tenant, int version, String by) {
        return new TenancyRegistration(UUID.randomUUID(), tenant, SOURCE, version, TenancyModel.DEDICATED_SCHEMAS,
                IDENTITY, List.of("ventas", "compras"), by, now(), "f".repeat(64));
    }

    private static TenancyDecision decision(TenancyRegistration registration, TenancyDecision.Kind kind, String by) {
        return new TenancyDecision(UUID.randomUUID(), registration.id(), kind, by, now());
    }
}
