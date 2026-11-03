package com.nexora.reports.api;

import com.nexora.reports.execution.application.ReportRunService;
import com.nexora.reports.execution.application.ReportRunService.RunReport;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.security.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * A4-B9 (D-B9-1): runs a report plan compiled by Nexora Semantic, reads the run and downloads its Excel file. The
 * tenant always comes from the access token; a run is visible only to its owner (same tenant, data source and
 * subject), and any other run is answered exactly as one that does not exist.
 * <ul>
 *   <li>{@code POST .../report-plans/{reportPlanId}/runs}: 201 with the {@code nexora-report-run/1} (COMPLETED,
 *       PARTIAL or REFUSED); no body (A4-B10: 400 {@code malformed_request} otherwise);</li>
 *   <li>{@code GET .../report-runs/{runId}}: the same document;</li>
 *   <li>{@code GET .../report-runs/{runId}/artifact}: the {@code .xlsx}, as an attachment named after the run.</li>
 * </ul>
 */
@RestController
public class ReportRunController {

    public static final String RUNS = "/api/v1/data-sources/{dataSourceId}/report-plans/{reportPlanId}/runs";
    public static final String RUN = "/api/v1/data-sources/{dataSourceId}/report-runs/{runId}";
    public static final String ARTIFACT = RUN + "/artifact";
    public static final String RUN_ID_HEADER = "X-Run-Id";
    public static final String SHA256_HEADER = "X-Content-SHA256";

    private static final Logger log = LoggerFactory.getLogger(ReportRunController.class);

    private final ReportRunService service;

    public ReportRunController(ReportRunService service) {
        this.service = service;
    }

    /**
     * The request takes no body (A4 proposal v3, section 5): one, even sent in chunks, is answered 400
     * {@code malformed_request} before anything else is done (no Semantic, no credentials, no run). The check runs
     * after authentication (401) and authorization (403), which keep their precedence.
     */
    @PostMapping(path = RUNS, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_EXECUTE)
    public ResponseEntity<Map<String, Object>> run(Requester requester, @PathVariable UUID dataSourceId,
                                                   @PathVariable UUID reportPlanId, HttpServletRequest request) {
        if (RequestBodies.present(request)) {
            throw new RequestBodyNotAllowedException();
        }
        RunReport report = service.run(requester, dataSourceId, reportPlanId);
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + dataSourceId + "/report-runs/"
                        + report.runId()))
                .header(RUN_ID_HEADER, report.runId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(report.document());
    }

    @GetMapping(path = RUN, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_EXECUTE)
    public ResponseEntity<Map<String, Object>> find(Requester requester, @PathVariable UUID dataSourceId,
                                                    @PathVariable UUID runId) {
        RunReport report = service.find(requester, dataSourceId, runId);
        return ResponseEntity.ok().header(RUN_ID_HEADER, report.runId().toString())
                .contentType(MediaType.APPLICATION_JSON).body(report.document());
    }

    /**
     * The bytes are written only once they are verified against their metadata (A4-B8); the delivery is recorded
     * after the last byte was written and flushed. A client that goes away midway leaves
     * {@code ARTIFACT_DELIVERY_FAILED}; nothing else can be done on a response already started.
     */
    @GetMapping(path = ARTIFACT)
    @PreAuthorize(Permission.REQUIRE_EXECUTE)
    public void artifact(Requester requester, @PathVariable UUID dataSourceId, @PathVariable UUID runId,
                         HttpServletResponse response) {
        try {
            service.download(requester, dataSourceId, runId, (artifact, content) -> {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType(artifact.mediaType());
                response.setContentLengthLong(content.length);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"nexora-report-" + artifact.runId() + ".xlsx\"");
                response.setHeader(SHA256_HEADER, artifact.sha256());
                response.setHeader(RUN_ID_HEADER, artifact.runId().toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.getOutputStream().write(content);
                response.flushBuffer();
            });
        } catch (IOException e) {
            log.info("artifact of run {} not delivered: the response could not be written", runId);
        }
    }
}
