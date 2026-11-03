package com.nexora.reports.api;

import com.nexora.reports.plan2.ContractFixtures;
import com.nexora.reports.render.xlsx.PoiTemporaryFiles;
import com.nexora.reports.support.AuthenticPlans;
import com.nexora.reports.support.MemoryMeasurements;
import com.nexora.reports.support.TemporarySpace;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static com.nexora.reports.support.RunApiSetup.BENCH_SCHEMAS;
import static com.nexora.reports.support.RunApiSetup.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4-B10-c.2, C2-3: what the two temporary-space test classes share. The authentic {@code volumen} case of B10-a is
 * run through the API of Reports (same documents, served byte for byte as in B10-c.1) while {@link TemporarySpace}
 * watches the storage directory of the class ({@code staging/} and {@code poifiles/}, where both the SXSSF files and
 * the renderer's own temporary file go) and the directory POI is using, if it is elsewhere. Test sources only.
 */
final class VolumeTemporaries {

    static final AuthenticPlans PLANS = AuthenticPlans.load();
    static final UUID TENANT = PLANS.tenantId();
    static final UUID SOURCE = PLANS.dataSourceId();
    static final String CASE = "volumen";
    static final Duration INTERVAL = Duration.ofMillis(2);

    private static final Map<String, Object> EVIDENCE = new TreeMap<>();

    private VolumeTemporaries() {
    }

    /** What one run left behind and what was observed while it ran. */
    record Observation(Map<String, Object> run, TemporarySpace.Snapshot before, TemporarySpace.Snapshot after,
                       Map<String, Object> observed) {
    }

    static void serve() {
        PLANS.serve(TENANT, SOURCE);
        PLANS.validities(TENANT, SOURCE, CASE, PLANS.of(CASE).validityFile());
    }

    /** Registers and approves (two people) the tenancy of the export's source, as B10-c.1 does. */
    static void register(MockMvc mvc, String suffix) throws Exception {
        String steward = token(TENANT, "steward-" + suffix, "HUMAN", "REPORTS_TENANCY_ADMIN");
        String approver = token(TENANT, "approver-" + suffix, "HUMAN", "REPORTS_TENANCY_ADMIN");
        byte[] created = mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + steward)
                        .contentType(MediaType.APPLICATION_JSON).content(BENCH_SCHEMAS))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        String id = (String) ContractFixtures.READER.readObject(created, "registration").get("id");
        mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/tenancy/registrations/" + id + "/approval")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver)).andExpect(status().isCreated());
    }

    /** The storage directory of the class and, if it is elsewhere, the directory POI is using now. */
    static List<Path> roots(Path storage) {
        List<Path> roots = new ArrayList<>(List.of(storage.toAbsolutePath().normalize()));
        Path poi = PoiTemporaryFiles.directory().toAbsolutePath().normalize();
        if (!poi.startsWith(roots.getFirst())) {
            roots.add(poi);
        }
        return roots;
    }

    /** Runs the volume case once, watching the temporary space before, during and after. */
    static Observation run(MockMvc mvc, Path storage, String analystSuffix) throws Exception {
        List<Path> roots = roots(storage);
        TemporarySpace.Snapshot before = TemporarySpace.snapshot(roots);
        TemporarySpace.Sampler sampler = TemporarySpace.sample(INTERVAL, roots);
        byte[] body;
        Map<String, Object> observed;
        try {
            body = mvc.perform(post("/api/v1/data-sources/" + SOURCE + "/report-plans/"
                            + PLANS.of(CASE).reportPlanId() + "/runs")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer "
                                    + token(TENANT, "analyst-" + analystSuffix, "HUMAN", "REPORT_RUNNER")))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        } finally {
            observed = sampler.stop();
        }
        TemporarySpace.Snapshot after = TemporarySpace.snapshot(roots);
        return new Observation(ContractFixtures.READER.readObject(body, "report-run"), before, after, observed);
    }

    /** Records an observation in {@code target/b10c2-evidence/temporales.json}, before anything is asserted. */
    static synchronized void record(String key, Path storage, Observation o, long maxFileBytes) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", o.run().get("status"));
        entry.put("refusal", o.run().get("refusal"));
        entry.put("artifact", o.run().get("artifact"));
        entry.put("maxFileBytes", maxFileBytes);
        entry.put("roots", roots(storage).stream().map(Path::toString).toList());
        entry.put("before", o.before().toMap());
        entry.put("after", o.after().toMap());
        entry.put("observedDuringRun", o.observed());
        entry.put("requirement128MiB", "open (D-C2-2): not implemented in production; not compared here");
        EVIDENCE.put(key, entry);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("case", CASE);
        content.put("cases", EVIDENCE);
        MemoryMeasurements.writeEvidence("temporales.json", content);
    }

    /** Names of files a run could leave behind: the renderer's file, SXSSF sheets, staged artifacts. */
    static List<String> leftovers(TemporarySpace.Snapshot before, TemporarySpace.Snapshot after) {
        List<String> left = new ArrayList<>();
        for (String name : after.files().keySet()) {
            if (!before.files().containsKey(name)) {
                left.add(name);
            }
        }
        return left;
    }
}
