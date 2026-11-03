package com.nexora.reports.support;

import com.nexora.reports.plan2.ContractFixtures;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import static com.nexora.reports.plan2.ContractFixtures.l;
import static com.nexora.reports.plan2.ContractFixtures.m;

/**
 * B10-c.1: the authentic documents compiled by the real Nexora Semantic in B10-a, copied byte for byte from the
 * preserved export ({@code Claude outputs/a4-b10a/windows-b10a-2026-11-02/a4-b10a-export}) into
 * {@code src/test/resources/semantic-b10a}. Nothing here edits, regenerates or reseals a document: they are served
 * to Reports exactly as Semantic emitted them, and every id (tenant, data source, report plans, logical plans) comes
 * from the export's {@code manifest.json}, never from the test code.
 * <p>
 * Before any use, {@link #load()} verifies, and fails on the first difference:
 * <ol>
 *   <li>the pin {@code semantic-b10a.sha256}: SHA-256 of {@code manifest.json} and {@code SHA256SUMS} as recorded in
 *       the master annex A2 when B10-a was verified on Windows;</li>
 *   <li>every line of the export's {@code SHA256SUMS};</li>
 *   <li>every entry of the manifest ({@code files}: SHA-256 and size).</li>
 * </ol>
 * The hashes prove that the copy is the archived export; that Semantic's compiler produced it rests on the
 * procedure of B10-a and its preserved evidence, not on these hashes. Test sources only.
 */
public final class AuthenticPlans {

    public static final String ROOT = "semantic-b10a/";
    public static final String PIN = "semantic-b10a.sha256";

    /** One exported case: its report plan, its logical plans by dataset and where its documents are. */
    public record Case(String id, UUID reportPlanId, String reportPlanFingerprint, String reportPlanFile,
                       String specFile, Map<String, UUID> planIds, Map<UUID, String> planFiles,
                       String validityStatus, String validityFile) {
    }

    private final Map<String, Object> manifest;
    private final UUID tenantId;
    private final UUID dataSourceId;
    private final Map<String, Case> cases = new LinkedHashMap<>();

    private AuthenticPlans(Map<String, Object> manifest) {
        this.manifest = manifest;
        this.tenantId = UUID.fromString((String) manifest.get("tenantId"));
        this.dataSourceId = UUID.fromString((String) manifest.get("dataSourceId"));
        for (Object o : l(manifest.get("cases"))) {
            Map<String, Object> c = m(o);
            Map<String, UUID> planIds = new LinkedHashMap<>();
            Map<UUID, String> planFiles = new LinkedHashMap<>();
            for (Object p : l(c.get("plans"))) {
                UUID planId = UUID.fromString((String) m(p).get("planId"));
                planIds.put((String) m(p).get("datasetId"), planId);
                planFiles.put(planId, (String) m(p).get("file"));
            }
            Map<String, Object> validity = m(c.get("validity"));
            String id = (String) c.get("id");
            cases.put(id, new Case(id, UUID.fromString((String) c.get("reportPlanId")),
                    (String) c.get("reportPlanFingerprint"), (String) c.get("reportPlan"),
                    (String) c.get("servedSpec"), planIds, planFiles, (String) validity.get("status"),
                    (String) validity.get("file")));
        }
    }

    /** The export, verified (pin, SHA256SUMS, manifest) before it is read. */
    public static AuthenticPlans load() {
        verify(relative -> ContractFixtures.bytes(ROOT + relative), ContractFixtures.bytes(PIN));
        return new AuthenticPlans(ContractFixtures.READER.readObject(ContractFixtures.bytes(ROOT + "manifest.json"),
                "manifest.json"));
    }

    /**
     * Verifies the export read by {@code files} (relative path → bytes) against {@code pin}; throws
     * {@link AssertionError} naming the first file that differs. Exposed so that a test can prove that a single
     * changed byte is detected.
     */
    public static void verify(Function<String, byte[]> files, byte[] pin) {
        Map<String, String> pinned = sums(new String(pin, StandardCharsets.UTF_8));
        if (!pinned.keySet().equals(java.util.Set.of("manifest.json", "SHA256SUMS"))) {
            throw new AssertionError("the pin must name exactly manifest.json and SHA256SUMS");
        }
        pinned.forEach((file, sha) -> check(file, sha, files.apply(file)));
        Map<String, String> sums = sums(new String(files.apply("SHA256SUMS"), StandardCharsets.UTF_8));
        if (sums.size() != 53 || !sums.containsKey("manifest.json")) {
            throw new AssertionError("SHA256SUMS must list the 53 other files of the export, got " + sums.size());
        }
        sums.forEach((file, sha) -> check(file, sha, files.apply(file)));
        Map<String, Object> manifest = ContractFixtures.READER.readObject(files.apply("manifest.json"),
                "manifest.json");
        Map<String, Object> listed = m(manifest.get("files"));
        if (listed.size() != 51) {
            throw new AssertionError("the manifest must list 51 files, got " + listed.size());
        }
        for (Map.Entry<String, Object> e : listed.entrySet()) {
            byte[] bytes = files.apply(e.getKey());
            check(e.getKey(), (String) m(e.getValue()).get("sha256"), bytes);
            if (((Number) m(e.getValue()).get("bytes")).longValue() != bytes.length) {
                throw new AssertionError("size differs from the manifest: " + e.getKey());
            }
            if (!sums.containsKey(e.getKey())) {
                throw new AssertionError("listed by the manifest but not by SHA256SUMS: " + e.getKey());
            }
        }
    }

    private static Map<String, String> sums(String text) {
        Map<String, String> sums = new TreeMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() < 67 || line.charAt(64) != ' ' || line.charAt(65) != ' ') {
                throw new AssertionError("not a sha256sum line: " + line);
            }
            if (sums.put(line.substring(66), line.substring(0, 64)) != null) {
                throw new AssertionError("listed twice: " + line.substring(66));
            }
        }
        return sums;
    }

    private static void check(String file, String expected, byte[] bytes) {
        if (bytes == null || !sha256(bytes).equals(expected)) {
            throw new AssertionError("SHA-256 differs from the recorded one: " + file);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ reading

    public UUID tenantId() {
        return tenantId;
    }

    public UUID dataSourceId() {
        return dataSourceId;
    }

    public Map<String, Object> manifest() {
        return manifest;
    }

    public Map<String, Case> cases() {
        return cases;
    }

    public Case of(String caseId) {
        return Objects.requireNonNull(cases.get(caseId), caseId);
    }

    public static byte[] bytes(String relative) {
        return ContractFixtures.bytes(ROOT + relative);
    }

    public static String text(String relative) {
        return new String(bytes(relative), StandardCharsets.UTF_8);
    }

    public static Map<String, Object> tree(String relative) {
        return ContractFixtures.READER.readObject(bytes(relative), relative);
    }

    public Map<String, Object> reportPlan(String caseId) {
        return tree(of(caseId).reportPlanFile());
    }

    public Map<String, Object> spec(String caseId) {
        return tree(of(caseId).specFile());
    }

    /** The logical plans of a case by plan id, in the order of the report plan. */
    public Map<UUID, Map<String, Object>> plans(String caseId) {
        Map<UUID, Map<String, Object>> plans = new LinkedHashMap<>();
        of(caseId).planFiles().forEach((id, file) -> plans.put(id, tree(file)));
        return plans;
    }

    /** The version of a catalog definition used by the export ({@code definitions} of the manifest). */
    public long definitionVersion(String key) {
        Map<String, Object> d = m(m(manifest.get("definitions")).get(key));
        Object version = d.containsKey("version") ? d.get("version") : d.get("versionAtCompile");
        return ((Number) version).longValue();
    }

    // ------------------------------------------------------------------ serving (fake Semantic transport)

    public static String path(UUID dataSourceId, UUID reportPlanId) {
        return "/api/v1/data-sources/" + dataSourceId + "/report-plans/" + reportPlanId;
    }

    /**
     * Serves, for {@code tenant} and {@code dataSourceId}, the data source document and the report plan, the
     * specification and the logical plans of every case, byte for byte as exported. The validity of each case is
     * served apart ({@link #validity}). Serving them for another tenant or source than the export's is how a test
     * reproduces a Semantic that answers with documents of somebody else.
     */
    public void serve(UUID tenant, UUID dataSourceId) {
        TestPlatform.semantic(tenant, "/api/v1/data-sources/" + dataSourceId, 200,
                text("respuestas/data-source.json"));
        serveDocuments(tenant, dataSourceId);
    }

    /**
     * B10-c.1-FIX1: serves, for {@code tenant} and {@code dataSourceId}, only the report plan, the specification and
     * the logical plans of every case, byte for byte as exported, without any data source document. A test that
     * needs Semantic to describe a source of its own (another tenant's) registers that description itself; it is
     * never one of the exported documents.
     */
    public void serveDocuments(UUID tenant, UUID dataSourceId) {
        for (Case c : cases.values()) {
            String base = path(dataSourceId, c.reportPlanId());
            TestPlatform.semantic(tenant, base, 200, text(c.reportPlanFile()));
            TestPlatform.semantic(tenant, base + "/spec", 200, text(c.specFile()));
            c.planFiles().forEach((planId, file) -> TestPlatform.semantic(tenant, base + "/logical-plans/" + planId,
                    200, text(file)));
        }
    }

    /** An exported validity response (for example {@code respuestas/pa-a.validity.json}), as Semantic answered. */
    public static TestPlatform.Response validity(String file) {
        return new TestPlatform.Response(200, text(file));
    }

    /** The validity responses of a case, served in order (before reading, after reading). */
    public void validities(UUID tenant, UUID dataSourceId, String caseId, String... files) {
        List<TestPlatform.Response> responses = new ArrayList<>();
        for (String file : files) {
            responses.add(validity(file));
        }
        TestPlatform.semanticSequence(tenant, path(dataSourceId, of(caseId).reportPlanId()) + "/validity",
                responses.toArray(TestPlatform.Response[]::new));
    }
}
