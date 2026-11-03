package com.nexora.reports.plan2.infrastructure;

import com.nexora.reports.plan2.domain.ContractSchema;
import com.nexora.reports.plan2.domain.ContractSchemas;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Loads the JSON Schemas of the contracts Reports reads in A4. They are copies of {@code nexora-contracts} bundled in
 * {@code src/main/resources/contracts}; a test checks that their bytes equal the contract files listed in the test
 * manifest ({@code src/test/resources/nexora-contracts/CONTRACTS.sha256}), so a divergence fails the build.
 * <p>
 * A missing schema, or one that uses a JSON Schema keyword {@link ContractSchema} does not implement, stops Reports
 * from starting (fails closed).
 */
public final class ClasspathContractSchemas {

    public static final String REPORT_SPEC = "contracts/report-spec/1/nexora-report-spec-1.schema.json";
    public static final String LOGICAL_PLAN = "contracts/logical-plan/2/nexora-logical-plan-2.schema.json";
    public static final String REPORT_PLAN = "contracts/report-plan/1/nexora-report-plan-1.schema.json";

    private ClasspathContractSchemas() {
    }

    public static ContractSchemas load(StrictJsonTreeReader reader) {
        return new ContractSchemas(compile(reader, REPORT_SPEC), compile(reader, LOGICAL_PLAN),
                compile(reader, REPORT_PLAN));
    }

    private static ContractSchema compile(StrictJsonTreeReader reader, String resource) {
        try (InputStream in = ClasspathContractSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing contract schema: " + resource);
            }
            return ContractSchema.compile(reader.readObject(in.readAllBytes(), resource));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read contract schema: " + resource, e);
        }
    }
}
