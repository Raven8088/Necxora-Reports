package com.nexora.reports.plan2.infrastructure;

import com.nexora.reports.plan2.domain.ContractSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * A4-B9 (D-B9-3): loads the schemas of the documents Reports <em>emits</em>, {@code nexora-report-run/1} and
 * {@code nexora-report-artifact/1}, and since A4-B10 {@code nexora-capabilities/1}. They are copies of
 * {@code nexora-contracts} bundled in {@code src/main/resources/contracts}; tests check their bytes against
 * {@code src/test/resources/nexora-contracts/RUN-CONTRACTS.sha256} and {@code CAPABILITIES-CONTRACT.sha256}. A
 * missing schema, or one with a keyword that {@link ContractSchema} does not implement, stops Reports from starting.
 */
public final class RunContractSchemas {

    public static final String REPORT_RUN = "contracts/report-run/1/nexora-report-run-1.schema.json";
    public static final String REPORT_ARTIFACT = "contracts/report-run/1/nexora-report-artifact-1.schema.json";
    /** A4-B10 (D-A4-8): the descriptor Reports publishes at {@code GET /api/v1/capabilities}. */
    public static final String CAPABILITIES = "contracts/capabilities/1/nexora-capabilities-1.schema.json";

    private RunContractSchemas() {
    }

    public static ContractSchema reportRun(StrictJsonTreeReader reader) {
        return compile(reader, REPORT_RUN);
    }

    public static ContractSchema reportArtifact(StrictJsonTreeReader reader) {
        return compile(reader, REPORT_ARTIFACT);
    }

    public static ContractSchema capabilities(StrictJsonTreeReader reader) {
        return compile(reader, CAPABILITIES);
    }

    private static ContractSchema compile(StrictJsonTreeReader reader, String resource) {
        try (InputStream in = RunContractSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing contract schema: " + resource);
            }
            return ContractSchema.compile(reader.readObject(in.readAllBytes(), resource));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read contract schema: " + resource, e);
        }
    }
}
