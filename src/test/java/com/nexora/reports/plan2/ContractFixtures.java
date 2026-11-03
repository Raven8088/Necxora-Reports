package com.nexora.reports.plan2;

import com.nexora.reports.plan2.domain.CanonicalJson;
import com.nexora.reports.plan2.domain.ContractSchemas;
import com.nexora.reports.plan2.domain.Sha256;
import com.nexora.reports.plan2.infrastructure.ClasspathContractSchemas;
import com.nexora.reports.plan2.infrastructure.JdkSha256;
import com.nexora.reports.plan2.infrastructure.StrictJsonTreeReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test access to the copy of {@code nexora-contracts} in {@code src/test/resources/nexora-contracts} (A4-B2,
 * authorized partial copy: report-spec/1, logical-plan/2, report-plan/1, their examples and the manifest). Documents
 * are read with the production reader and returned as fresh mutable trees, so tests derive cases by mutating copies
 * in memory; the files are never modified.
 */
public final class ContractFixtures {

    public static final String ROOT = "nexora-contracts/";
    public static final StrictJsonTreeReader READER = new StrictJsonTreeReader();
    public static final Sha256 SHA256 = new JdkSha256();

    private ContractFixtures() {
    }

    public static byte[] bytes(String resource) {
        try (InputStream in = ContractFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A document of the copied contracts, by path relative to the contracts root (e.g. examples/valid/x.json). */
    public static Map<String, Object> read(String relative) {
        return READER.readObject(bytes(ROOT + relative), relative);
    }

    public static ContractSchemas schemas() {
        return ClasspathContractSchemas.load(READER);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> m(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> l(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, copy(v)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(copy(v)));
            return (T) copy;
        }
        return value;
    }

    /** The real PA-A documents (report plan, specification and its three logical plans), mutable. */
    public static final class PaA {

        public final Map<String, Object> reportPlan = read("examples/valid/pa-a.report-plan.json");
        public final Map<String, Object> spec = read("examples/valid/pa-a.report-spec.json");
        public final Map<UUID, Map<String, Object>> plans = new LinkedHashMap<>();
        public UUID tenantId = UUID.fromString((String) reportPlan.get("tenantId"));
        public UUID dataSourceId = UUID.fromString((String) reportPlan.get("dataSourceId"));

        public PaA() {
            for (Object ref : l(reportPlan.get("plans"))) {
                String datasetId = (String) m(ref).get("datasetId");
                plans.put(UUID.fromString((String) m(ref).get("planId")),
                        read("examples/valid/pa-a." + datasetId + ".logical-plan.json"));
            }
        }

        public Map<String, Object> plan(String datasetId) {
            return plans.values().stream().filter(p -> datasetId.equals(p.get("datasetId"))).findFirst()
                    .orElseThrow();
        }

        public List<Object> operations(String datasetId) {
            return l(plan(datasetId).get("operations"));
        }

        /**
         * Recomputes every fingerprint (specification, plans, report plan) so that a mutation isolates the rule under
         * test instead of failing on a fingerprint first.
         */
        public void reseal() {
            String specFingerprint = CanonicalJson.sha256(spec, SHA256);
            reportPlan.put("specFingerprint", specFingerprint);
            for (Object ref : l(reportPlan.get("plans"))) {
                Map<String, Object> plan = plans.get(UUID.fromString((String) m(ref).get("planId")));
                if (plan == null) {
                    continue;
                }
                plan.put("specFingerprint", specFingerprint);
                plan.put("fingerprint", CanonicalJson.fingerprintOf(plan, SHA256));
                m(ref).put("planFingerprint", plan.get("fingerprint"));
            }
            reportPlan.put("fingerprint", CanonicalJson.fingerprintOf(reportPlan, SHA256));
        }
    }
}
