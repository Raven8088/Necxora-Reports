package com.nexora.reports.api;

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
 * A4-B10-c.2, C2-3 (R4-6, failure): the same volume run with the file limit of this context lowered to 64 KiB, a test
 * of the mechanism as in B10-O5. The limit is above the renderer's own minimum for this workbook (about 25 KB measured
 * by Claude with the real layout) and below the file it writes (about 171 KB, B10-c.1), so the run fails while the
 * workbook is being written, with its temporary files open: it is refused {@code artifact_too_large}, without
 * artifact, and the temporary space is left as it was found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RunProbes.class)
class VolumeTemporariesFailureIntegrationTest {

    static final long TEST_MAX_FILE_BYTES = 64 * 1024;

    private static Path storage;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        storage = Files.createTempDirectory("nexora-b10c2-temporales-fallo");
        RunApiSetup.common(registry, storage);
        RunApiSetup.account(registry, 0, VolumeTemporaries.TENANT, "reports-b10c2-ko", "secret-b10c2-ko");
        RunApiSetup.source(registry, 0, VolumeTemporaries.TENANT, VolumeTemporaries.SOURCE);
        registry.add("nexora.reports.storage.max-file-bytes", () -> Long.toString(TEST_MAX_FILE_BYTES));
    }

    @Test
    void aVolumeRunThatFailsWhileWritingLeavesTheTemporarySpaceAsItFoundIt() throws Exception {
        VolumeTemporaries.serve();
        VolumeTemporaries.register(mvc, "b10c2-ko");

        VolumeTemporaries.Observation o = VolumeTemporaries.run(mvc, storage, "b10c2-ko");
        VolumeTemporaries.record("fallo", storage, o, TEST_MAX_FILE_BYTES);

        assertThat(o.run().get("status")).isEqualTo("REFUSED");
        assertThat(m(o.run().get("refusal")).get("code")).isEqualTo("artifact_too_large");
        assertThat(o.run().get("artifact")).isNull();
        assertThat(VolumeTemporaries.leftovers(o.before(), o.after())).isEmpty();
        assertThat(o.after().files()).isEqualTo(o.before().files());
    }
}
