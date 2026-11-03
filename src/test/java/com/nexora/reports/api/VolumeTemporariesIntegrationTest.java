package com.nexora.reports.api;

import com.nexora.reports.run.domain.StorageLimits;
import com.nexora.reports.support.RunApiSetup;
import com.nexora.reports.support.RunProbes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.nexora.reports.plan2.ContractFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B10-c.2, C2-3 (R4-6, success): the authentic volume case run through the API with the production limits leaves
 * the temporary space exactly as it found it; the largest space observed while it ran is recorded as a lower bound of
 * the peak ({@code target/b10c2-evidence/temporales.json}). The requirement of 128 MiB of temporary disk per
 * presentation stays open (D-C2-2): nothing here implements or replaces it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RunProbes.class)
class VolumeTemporariesIntegrationTest {

    private static Path storage;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storage = Files.createTempDirectory("nexora-b10c2-temporales");
        RunApiSetup.common(registry, storage);
        RunApiSetup.account(registry, 0, VolumeTemporaries.TENANT, "reports-b10c2-ok", "secret-b10c2-ok");
        RunApiSetup.source(registry, 0, VolumeTemporaries.TENANT, VolumeTemporaries.SOURCE);
    }

    @Test
    void theVolumeRunLeavesTheTemporarySpaceAsItFoundIt() throws Exception {
        VolumeTemporaries.serve();
        VolumeTemporaries.register(mvc, "b10c2-ok");

        VolumeTemporaries.Observation o = VolumeTemporaries.run(mvc, storage, "b10c2-ok");
        VolumeTemporaries.record("exito", storage, o, StorageLimits.DEFAULTS.maxFileBytes());

        assertThat(o.run().get("status")).isEqualTo("PARTIAL");
        assertThat(m(o.run().get("artifact")).get("sizeBytes")).isNotNull();
        assertThat(VolumeTemporaries.leftovers(o.before(), o.after())).isEmpty();
        assertThat(o.after().files()).isEqualTo(o.before().files());
    }
}
