package com.nexora.reports.support;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * A4-B8: a database of its own in the R1 test container ({@link TestDatabase}, {@code postgres:17.6}), migrated by
 * Flyway (V1, V2 and V3) as the container superuser exactly as Reports does, and used through the restricted
 * application role. The superuser connection exists only to arrange states a test cannot reach otherwise (an expired
 * artifact, corrupted content); production code never has it.
 */
public final class RunRegistryDatabase {

    private final String name;

    private RunRegistryDatabase(String name) {
        this.name = name;
    }

    public static RunRegistryDatabase create(String name) {
        try (Connection c = TestDatabase.superuser("nexora_reports"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        RunRegistryDatabase db = new RunRegistryDatabase(name);
        Flyway.configure().dataSource(db.url(), TestDatabase.superuserName(), TestDatabase.superuserPassword())
                .schemas("reports").defaultSchema("reports")
                .placeholders(Map.of("appRole", TestDatabase.APP_ROLE)).locations("classpath:db/migration")
                .load().migrate();
        return db;
    }

    public String url() {
        return "jdbc:postgresql://" + TestDatabase.host() + ":" + TestDatabase.port() + "/" + name
                + "?currentSchema=reports";
    }

    /** The application role, as Reports uses it. */
    public DataSource app() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url());
        ds.setUser(TestDatabase.APP_ROLE);
        ds.setPassword(TestDatabase.APP_PASSWORD);
        return ds;
    }

    public Connection appConnection() throws SQLException {
        return DriverManager.getConnection(url(), TestDatabase.APP_ROLE, TestDatabase.APP_PASSWORD);
    }

    /** Test-only: the container superuser, to arrange states that the application can never produce. */
    public Connection superuser() throws SQLException {
        return DriverManager.getConnection(url(), TestDatabase.superuserName(), TestDatabase.superuserPassword());
    }
}
