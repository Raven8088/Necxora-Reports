package com.nexora.reports.support;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.plan2.ContractFixtures;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * A4-B4: one PostgreSQL ({@code postgres:17.6} by default, {@code -Dnexora.test.postgres-image=...} to change it)
 * with the synthetic bench of A0 loaded from its verified copy ({@code src/test/resources/nexora-testbench}) and its
 * read-only role, started once for the test classes that use it.
 * <p>
 * The bench itself is never modified. Tests that need to change a schema, lock a table or insert rows do it in the
 * auxiliary schema {@value #AUX}, created here empty, owned by the superuser and readable by the bench role only
 * through the grants each test gives explicitly (A4 proposal v3, section 7).
 */
public final class BenchDatabase {

    public static final String DATABASE = "nexora_bench_reportes";
    public static final String READER = "nexora_bench_lector";
    public static final String READER_PASSWORD = "bench-test-password";
    public static final String AUX = "b4_aux";

    private static final String BENCH = "nexora-testbench/reportes/";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName
            .parse(System.getProperty("nexora.test.postgres-image", "postgres:17.6"))
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE);

    static {
        POSTGRES.start();
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            for (String script : List.of("sql/01-esquema.sql", "sql/02-datos.sql", "sql/03-rol-lector.sql")) {
                s.execute(new String(ContractFixtures.bytes(BENCH + script), StandardCharsets.UTF_8));
            }
            s.execute("ALTER ROLE " + READER + " PASSWORD '" + READER_PASSWORD + "'");
            s.execute("CREATE SCHEMA " + AUX);
            s.execute("GRANT USAGE ON SCHEMA " + AUX + " TO " + READER);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private BenchDatabase() {
    }

    /** A superuser connection to the bench database, in autocommit mode. */
    public static Connection superuser() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs statements as superuser, each one committed. */
    public static void exec(String... statements) {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Read-only credentials of the bench for (tenant, data source). */
    public static DataSourceCredentials reader(UUID tenantId, UUID dataSourceId, int maxConcurrent) {
        return new DataSourceCredentials(tenantId, dataSourceId, POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE, READER, READER_PASSWORD,
                "disable", maxConcurrent);
    }
}
