package com.nexora.reports.api;

import com.nexora.reports.api.ExecutionDtos.ExecutionRequest;
import com.nexora.reports.api.ExecutionDtos.ExecutionResponse;
import com.nexora.reports.execution.application.ReportExecutionService;
import com.nexora.reports.execution.domain.ReportResult;
import com.nexora.reports.execution.domain.Requester;
import com.nexora.reports.security.Permission;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Executes a logical plan previously created in Nexora Semantic. The request identifies the plan by data source and
 * plan id only; the tenant comes from the access token and the plan is always fetched from Semantic.
 */
@RestController
public class ExecutionController {

    public static final String EXECUTIONS = "/api/v1/data-sources/{dataSourceId}/plans/{planId}/executions";
    public static final String EXECUTION_ID_HEADER = "X-Execution-Id";

    private final ReportExecutionService service;

    public ExecutionController(ReportExecutionService service) {
        this.service = service;
    }

    @PostMapping(path = EXECUTIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.REQUIRE_EXECUTE)
    public ResponseEntity<ExecutionResponse> execute(Requester requester, @PathVariable UUID dataSourceId,
                                                     @PathVariable UUID planId,
                                                     @RequestBody(required = false) ExecutionRequest body) {
        Integer maxRows = body == null ? null : body.maxRows();
        if (maxRows != null && (maxRows < 1 || maxRows > service.limits().maxRowsCeiling())) {
            throw new InvalidRequestException("maxRows", "must be between 1 and " + service.limits().maxRowsCeiling());
        }
        ReportResult result = service.execute(requester, dataSourceId, planId, maxRows);
        return ResponseEntity.ok().header(EXECUTION_ID_HEADER, result.executionId().toString())
                .body(ExecutionResponse.from(result));
    }
}
