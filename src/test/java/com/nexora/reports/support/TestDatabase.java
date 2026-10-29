package com.nexora.reports.support;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * One PostgreSQL container per JVM with two databases: {@code nexora_reports} (Reports' audit, migrated by Flyway as
 * the container superuser, used by the application through the restricted {@code nexora_reports_app} role) and
 * {@code enterprise} (synthetic company data, read through the {@code nexora_reader} role).
 * <p>
 * The image is {@code postgres:17.6} by default; set {@code -Dnexora.test.postgres-image=...} to use another
 * compatible image (it is declared as a substitute for {@code postgres}).
 */
public final class TestDatabase {

    public static final String READER = "nexora_reader";
    public static final String READER_PASSWORD = "reader-test-password";
    public static final String WRITER = "nexora_writer";
    public static final String WRITER_PASSWORD = "writer-test-password";
    public static final String APP_ROLE = "nexora_reports_app";
    public static final String APP_PASSWORD = "app-test-password";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse(System.getProperty("nexora.test.postgres-image", "postgres:17.6"))
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nexora_reports");

    static {
        POSTGRES.start();
        try (Connection c = superuser("nexora_reports"); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            s.execute("CREATE DATABASE enterprise");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        try (Connection c = superuser("enterprise"); Statement s = c.createStatement()) {
            s.execute(resource("/testcontainers/enterprise.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestDatabase() {
    }

    public static String host() {
        return POSTGRES.getHost();
    }

    public static int port() {
        return POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    public static String reportsJdbcUrl() {
        return "jdbc:postgresql://" + host() + ":" + port() + "/nexora_reports?currentSchema=reports";
    }

    public static String superuserName() {
        return POSTGRES.getUsername();
    }

    public static String superuserPassword() {
        return POSTGRES.getPassword();
    }

    public static Connection superuser(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Read-only credentials of the synthetic enterprise database. */
    public static DataSourceCredentials reader(UUID tenantId, UUID dataSourceId, int maxConcurrent) {
        return credentials(tenantId, dataSourceId, READER, READER_PASSWORD, maxConcurrent);
    }

    public static DataSourceCredentials credentials(UUID tenantId, UUID dataSourceId, String user, String password,
                                                    int maxConcurrent) {
        return new DataSourceCredentials(tenantId, dataSourceId, host(), port(), "enterprise", user, password,
                "disable", maxConcurrent);
    }

    private static String resource(String name) {
        try (InputStream in = TestDatabase.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
