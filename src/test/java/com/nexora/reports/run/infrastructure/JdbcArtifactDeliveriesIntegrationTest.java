package com.nexora.reports.run.infrastructure;

import com.nexora.reports.render.RenderFixtures;
import com.nexora.reports.render.xlsx.XlsxRenderer;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.run.domain.Retrieval;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOwner;
import com.nexora.reports.run.domain.RunScope;
import com.nexora.reports.run.domain.RunStart;
import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.run.domain.StoredArtifact;
import com.nexora.reports.support.RunRegistryDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B9 (D-B9-4) with PostgreSQL 17.6: migration V4 widens the closed list of events with the delivery to an HTTP
 * client, apart from the retrieval from the store, and nothing else: every other kind is still refused, events are
 * still append-only and carry no content.
 */
class JdbcArtifactDeliveriesIntegrationTest {

    private static final RunOwner ANA = new RunOwner("ana", RunOwner.Type.HUMAN);
    private static RunRegistryDatabase db;
    private static DataSource app;
    private static Path dir;
    private static StoredArtifact artifact;
    private static RunScope scope;

    @BeforeAll
    static void setUp() throws IOException {
        db = RunRegistryDatabase.create("run_deliveries");
        app = db.app();
        dir = Files.createTempDirectory("b9-it");
        Path file = dir.resolve("pa-a.xlsx");
        RenderedArtifact rendered;
        try (OutputStream out = Files.newOutputStream(file)) {
            rendered = new XlsxRenderer().render(RenderFixtures.paA(), out, RenderLimits.DEFAULTS);
        }
        JdbcRunStore store = new JdbcRunStore(app, StorageLimits.DEFAULTS);
        RunStart start = new RunStart(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ANA,
                Duration.ofMinutes(5));
        store.begin(start);
        artifact = store.finishWithArtifact(start.ref(), RunOutcome.of(RenderFixtures.paA()), rendered, file)
                .artifact().orElseThrow();
        scope = new RunScope(start.tenantId(), start.dataSourceId(), ANA.subject());
    }

    @AfterAll
    static void tearDown() throws IOException {
        ArtifactStagingTest.delete(dir);
    }

    @Test
    void aDeliveryIsRecordedApartFromTheRetrievalWithTheDigestOfTheBytes() throws SQLException {
        Retrieval retrieval = new JdbcRunReader(app, StorageLimits.DEFAULTS).open(scope, artifact.runId());
        assertThat(retrieval).isInstanceOf(Retrieval.Available.class);
        JdbcArtifactDeliveries deliveries = new JdbcArtifactDeliveries(app);

        deliveries.delivered(artifact);
        deliveries.deliveryFailed(artifact, "client_write_failed");

        List<String> rows = events(artifact.runId());
        assertThat(rows).contains("ARTIFACT_RETRIEVED|null|" + artifact.sha256() + "|" + artifact.sizeBytes(),
                "ARTIFACT_DELIVERED|null|" + artifact.sha256() + "|" + artifact.sizeBytes(),
                "ARTIFACT_DELIVERY_FAILED|client_write_failed|" + artifact.sha256() + "|" + artifact.sizeBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1abc", "client write failed", "x'); DROP TABLE report_run;--"})
    void aDeliveryDetailOutsideItsPatternIsRefusedBeforeTheDatabase(String detail) {
        assertThatThrownBy(() -> new JdbcArtifactDeliveries(app).deliveryFailed(artifact, detail))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theListOfEventKindsIsStillClosedAndEventsStillAppendOnly() throws SQLException {
        try (Connection c = db.appConnection(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("INSERT INTO report_run_event (tenant_id, run_id, kind) VALUES ('"
                    + artifact.tenantId() + "', '" + artifact.runId() + "', 'ARTIFACT_LOST')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("UPDATE report_run_event SET kind = 'ARTIFACT_DELIVERED'"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection c = db.superuser(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("DELETE FROM report_run_event")).isInstanceOf(SQLException.class);
            try (ResultSet rs = s.executeQuery("SELECT max(version) FROM flyway_schema_history WHERE success")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("4");
            }
        }
    }

    @Test
    void aDeliveryOfAnotherTenantsRunIsRefusedByTheDatabase() {
        StoredArtifact foreign = new StoredArtifact(artifact.artifactId(), artifact.runId(), UUID.randomUUID(),
                artifact.dataSourceId(), artifact.mediaType(), artifact.excelContract(), artifact.sha256(),
                artifact.sizeBytes(), artifact.sheets(), artifact.createdAt(), artifact.expiresAt());

        assertThatThrownBy(() -> new JdbcArtifactDeliveries(app).delivered(foreign))
                .isInstanceOf(com.nexora.reports.run.domain.RunStoreException.class);
    }

    private static List<String> events(UUID runId) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.appConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT kind, detail, sha256, size_bytes FROM report_run_event WHERE run_id = ? ORDER BY recorded_at")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getLong(4));
                }
            }
        }
        return rows;
    }
}
