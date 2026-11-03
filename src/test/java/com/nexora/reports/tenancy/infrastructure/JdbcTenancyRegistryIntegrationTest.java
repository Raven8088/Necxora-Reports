package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.support.TestDatabase;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;
import com.nexora.reports.tenancy.domain.TenancyDecision;
import com.nexora.reports.tenancy.domain.TenancyModel;
import com.nexora.reports.tenancy.domain.TenancyRegistration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
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

    private static TenancyRegistration registration(UUID tenant, int version, String by) {
        return new TenancyRegistration(UUID.randomUUID(), tenant, SOURCE, version, TenancyModel.DEDICATED_SCHEMAS,
                IDENTITY, List.of("ventas", "compras"), by, now(), "f".repeat(64));
    }

    private static TenancyDecision decision(TenancyRegistration registration, TenancyDecision.Kind kind, String by) {
        return new TenancyDecision(UUID.randomUUID(), registration.id(), kind, by, now());
    }
}
