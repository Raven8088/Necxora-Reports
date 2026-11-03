package com.nexora.reports.support;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A4-B5: a PostgreSQL ({@code postgres:17.6} by default) with <b>real TLS</b>, for the tenancy checks. A self-signed
 * certificate for the Docker host name is generated at test time with the JDK's {@code keytool} (nothing is
 * committed, no tool is installed); the server gets it at start-up and clients trust it through {@code sslrootcert}
 * with {@code sslmode=verify-full}, so the driver really verifies the certificate and the host name. A second,
 * unrelated certificate ({@link #wrongCa()}) lets tests prove that {@code verify-full} refuses a server it cannot
 * verify.
 * <p>
 * Layout (created here, all synthetic):
 * <ul>
 *   <li>{@code tenencia_a}: database dedicated to tenant A (schema {@code ventas}), read by {@code lector_a};</li>
 *   <li>{@code compartida}: a shared database with schemas {@code tenant_a} and {@code tenant_b}, read by
 *       {@code lector_sa} (and its twin {@code lector_sa2}) and {@code lector_sb}; {@code public} is not usable;</li>
 *   <li>other databases are created by the tests that need them.</li>
 * </ul>
 */
public final class TenantDatabase {

    public static final String PASSWORD = "tenancy-test-password";
    private static final char[] STORE_PASSWORD = "tenancy-test-store".toCharArray();

    private static final Path TLS;
    private static final Path ROOT_CERT;
    private static final Path WRONG_CA;
    private static final PostgreSQLContainer POSTGRES;

    static {
        try {
            TLS = Files.createTempDirectory("nexora-b5-tls");
            String host = DockerClientFactory.instance().dockerHostIpAddress();
            Pem server = certificate(TLS.resolve("server.p12"), host);
            Pem other = certificate(TLS.resolve("other.p12"), host);
            ROOT_CERT = Files.writeString(TLS.resolve("root.crt"), server.certificate());
            WRONG_CA = Files.writeString(TLS.resolve("wrong-root.crt"), other.certificate());
            POSTGRES = new PostgreSQLContainer(DockerImageName
                    .parse(System.getProperty("nexora.test.postgres-image", "postgres:17.6"))
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("nexora_tenancy")
                    .withCopyToContainer(Transferable.of(server.certificate().getBytes(StandardCharsets.US_ASCII),
                            0644), "/tmp/tls/server.crt")
                    .withCopyToContainer(Transferable.of(server.key().getBytes(StandardCharsets.US_ASCII), 0600),
                            "/tmp/tls/server.key")
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", """
                            mkdir -p /var/lib/postgresql/tls \
                            && cp /tmp/tls/server.crt /tmp/tls/server.key /var/lib/postgresql/tls/ \
                            && chown -R postgres:postgres /var/lib/postgresql/tls \
                            && chmod 600 /var/lib/postgresql/tls/server.key \
                            && exec docker-entrypoint.sh "$@" -c ssl=on \
                               -c ssl_cert_file=/var/lib/postgresql/tls/server.crt \
                               -c ssl_key_file=/var/lib/postgresql/tls/server.key""", "sh"));
            POSTGRES.start();
            setUp();
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the TLS test database", e);
        }
    }

    private TenantDatabase() {
    }

    /** Path of the CA certificate that verifies this server. */
    public static String rootCert() {
        return ROOT_CERT.toString();
    }

    /** Path of an unrelated CA certificate: {@code verify-full} with it must fail. */
    public static String wrongCa() {
        return WRONG_CA.toString();
    }

    public static String host() {
        return POSTGRES.getHost();
    }

    public static int port() {
        return POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    /** Read-only credentials, with {@code verify-full} against this server's certificate unless told otherwise. */
    public static DataSourceCredentials credentials(UUID tenant, UUID source, String database, String user,
                                                    String sslMode, String rootCert) {
        return new DataSourceCredentials(tenant, source, host(), port(), database, user, PASSWORD, sslMode, 4,
                rootCert);
    }

    public static DataSourceCredentials credentials(UUID tenant, UUID source, String database, String user) {
        return credentials(tenant, source, database, user, "verify-full", rootCert());
    }

    /**
     * A4-B10: a superuser connection to a database of this server, for a test that must hold something open (for
     * example a lock) while Reports works; the caller closes it.
     */
    public static Connection superuser(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + host() + ":" + port() + "/" + database
                + "?sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs statements as superuser in a database, each one committed. */
    public static void exec(String database, String... statements) {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host() + ":" + port() + "/"
                + database + "?sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setUp() {
        List<String> roles = new ArrayList<>();
        for (String role : List.of("lector_a", "lector_sa", "lector_sa2", "lector_sb")) {
            roles.add("CREATE ROLE " + role + " LOGIN PASSWORD '" + PASSWORD + "'");
            roles.add("ALTER ROLE " + role + " SET default_transaction_read_only = on");
        }
        roles.add("CREATE DATABASE tenencia_a");
        roles.add("CREATE DATABASE compartida");
        exec("nexora_tenancy", roles.toArray(String[]::new));
        exec("tenencia_a", "CREATE SCHEMA ventas",
                "CREATE TABLE ventas.pedidos (id integer PRIMARY KEY, cliente text)",
                "INSERT INTO ventas.pedidos VALUES (1, 'Cliente A1'), (2, 'Cliente A2')",
                "GRANT USAGE ON SCHEMA ventas TO lector_a", "GRANT SELECT ON ventas.pedidos TO lector_a");
        exec("compartida", "REVOKE ALL ON SCHEMA public FROM PUBLIC",
                "CREATE SCHEMA tenant_a", "CREATE SCHEMA tenant_b",
                "CREATE TABLE tenant_a.pedidos (id integer PRIMARY KEY, cliente text)",
                "CREATE TABLE tenant_b.pedidos (id integer PRIMARY KEY, cliente text)",
                "INSERT INTO tenant_a.pedidos VALUES (1, 'Solo de A')",
                "INSERT INTO tenant_b.pedidos VALUES (1, 'Solo de B'), (2, 'Otro de B')",
                "GRANT USAGE ON SCHEMA tenant_a TO lector_sa, lector_sa2",
                "GRANT SELECT ON tenant_a.pedidos TO lector_sa, lector_sa2",
                "GRANT USAGE ON SCHEMA tenant_b TO lector_sb", "GRANT SELECT ON tenant_b.pedidos TO lector_sb");
    }

    private record Pem(String certificate, String key) {
    }

    /** A self-signed certificate for the Docker host, made by keytool and converted to PEM with the JDK. */
    private static Pem certificate(Path store, String host) throws Exception {
        String san = "dns:localhost,ip:127.0.0.1";
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            san += host.matches("[0-9.]+") ? ",ip:" + host : ",dns:" + host;
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "3", "-dname", "CN=localhost", "-ext", "SAN=" + san,
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(STORE_PASSWORD),
                "-keypass", new String(STORE_PASSWORD), "-noprompt").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keyStore.load(in, STORE_PASSWORD);
        }
        Certificate certificate = keyStore.getCertificate("server");
        Key key = keyStore.getKey("server", STORE_PASSWORD);
        return new Pem(pem("CERTIFICATE", certificate.getEncoded()), pem("PRIVATE KEY", key.getEncoded()));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
