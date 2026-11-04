package com.nexora.reports.api;

import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.execution.domain.RedactedFailure;
import com.nexora.reports.execution.domain.RunException;
import com.nexora.reports.plan.domain.PlanNotExecutableException;
import com.nexora.reports.tenancy.domain.TenancyException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to a stable problem document. Internal reasons go to the logs (with the correlation id), never
 * to the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private record Mapping(HttpStatus status, String code, String detail) {
    }

    private static final Map<ErrorCode, Mapping> EXECUTION = new EnumMap<>(ErrorCode.class);

    static {
        map(ErrorCode.REQUESTER_NOT_ALLOWED, HttpStatus.FORBIDDEN, "requester_not_allowed",
                "This principal is not allowed to execute reports.");
        map(ErrorCode.DATA_SOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "not_found", "Resource not found.");
        map(ErrorCode.PLAN_NOT_FOUND, HttpStatus.NOT_FOUND, "not_found", "Resource not found.");
        map(ErrorCode.DATA_SOURCE_NOT_EXECUTABLE, HttpStatus.CONFLICT, "data_source_not_executable",
                "The data source is not an active PostgreSQL source.");
        map(ErrorCode.DATA_SOURCE_NOT_CONFIGURED, HttpStatus.CONFLICT, "data_source_not_configured",
                "No read-only connection is configured for this data source.");
        map(ErrorCode.PLAN_STALE, HttpStatus.CONFLICT, "plan_stale",
                "The plan is STALE in Semantic and cannot be executed.");
        map(ErrorCode.PLAN_CHANGED_DURING_EXECUTION, HttpStatus.CONFLICT, "plan_changed_during_execution",
                "The plan stopped being executable during the execution; the result was discarded.");
        map(ErrorCode.PHYSICAL_SCHEMA_MISMATCH, HttpStatus.CONFLICT, "physical_schema_mismatch",
                "The live database schema does not match the plan.");
        map(ErrorCode.TOO_MANY_EXECUTIONS, HttpStatus.TOO_MANY_REQUESTS, "too_many_executions",
                "Too many concurrent executions; retry later.");
        map(ErrorCode.RESULT_TOO_LARGE, HttpStatus.CONFLICT, "result_too_large",
                "The result exceeds the row or memory limit of an execution.");
        map(ErrorCode.VALUE_NOT_REPRESENTABLE, HttpStatus.CONFLICT, "value_not_representable",
                "A value of the data source cannot be represented exactly in its output type.");
        map(ErrorCode.TENANT_SCOPE_UNVERIFIED, HttpStatus.CONFLICT, "tenant_scope_unverified",
                "The tenancy of the data source could not be verified; nothing was read.");
        map(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, HttpStatus.SERVICE_UNAVAILABLE, "plan_validity_unconfirmed",
                "Semantic could not confirm that the plan is current.");
        map(ErrorCode.SEMANTIC_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "semantic_unavailable",
                "Nexora Semantic is not available.");
        map(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, HttpStatus.BAD_GATEWAY, "semantic_contract_violation",
                "Nexora Semantic returned an unexpected response.");
        map(ErrorCode.SERVICE_IDENTITY_INVALID, HttpStatus.BAD_GATEWAY, "service_identity_invalid",
                "The Reports service identity for this tenant is not valid.");
        map(ErrorCode.DATA_SOURCE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "data_source_unavailable",
                "The data source is not available.");
        map(ErrorCode.DATA_SOURCE_TARGET_NOT_ALLOWED, HttpStatus.INTERNAL_SERVER_ERROR,
                "data_source_target_not_allowed", "The configured data source destination is not allowed.");
        map(ErrorCode.DATA_SOURCE_ROLE_NOT_READ_ONLY, HttpStatus.INTERNAL_SERVER_ERROR,
                "data_source_role_not_read_only", "The configured database role is not strictly read-only.");
        map(ErrorCode.EXECUTION_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, "execution_timeout",
                "The execution exceeded its time limit.");
        map(ErrorCode.EXECUTION_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, "execution_failed",
                "The execution failed.");
        map(ErrorCode.AUDIT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "audit_unavailable",
                "The execution could not be audited; no result is returned.");
        // A4-B9: report runs and their artifacts.
        map(ErrorCode.RUN_NOT_FOUND, HttpStatus.NOT_FOUND, "not_found", "Resource not found.");
        map(ErrorCode.RUN_IN_PROGRESS, HttpStatus.CONFLICT, "run_in_progress",
                "The run has not finished yet; retry later.");
        map(ErrorCode.RUN_RECORDED_AS_FAILED, HttpStatus.CONFLICT, "run_failed",
                "The run failed internally; it has no report.");
        map(ErrorCode.RUN_INTERRUPTED, HttpStatus.CONFLICT, "run_interrupted",
                "The run did not finish before its deadline; it has no report.");
        map(ErrorCode.RUN_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, "run_failed",
                "The run failed internally; it was recorded and has no report.");
        map(ErrorCode.REPORT_PLAN_INVALID, HttpStatus.UNPROCESSABLE_CONTENT, "report_plan_invalid",
                "The report plan is not valid; the run was refused and no report describes it.");
        map(ErrorCode.ARTIFACT_GONE, HttpStatus.GONE, "artifact_gone", "The file of this run expired or was purged.");
        map(ErrorCode.ARTIFACT_INTEGRITY_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, "artifact_integrity_failed",
                "The stored file does not match its metadata; it is not delivered.");
        map(ErrorCode.TOO_MANY_DOWNLOADS, HttpStatus.TOO_MANY_REQUESTS, "too_many_downloads",
                "Too many concurrent downloads; retry later.");
        map(ErrorCode.RUN_STORAGE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "run_storage_unavailable",
                "The run registry is not available.");
        // B10-O3 (D-O3-3): only reached as the refusal of a started run; mapped like every code.
        map(ErrorCode.DATA_SOURCE_CONFIGURATION_CHANGED, HttpStatus.CONFLICT, "data_source_configuration_changed",
                "The configuration of the data source changed after the run started; nothing was read.");
        for (ErrorCode code : ErrorCode.values()) {
            if (!EXECUTION.containsKey(code)) {
                throw new ExceptionInInitializerError("unmapped error code " + code);
            }
        }
    }

    private static final Map<TenancyException.Reason, Mapping> TENANCY = new EnumMap<>(TenancyException.Reason.class);

    static {
        tenancy(TenancyException.Reason.NOT_A_PERSON, HttpStatus.FORBIDDEN, "tenancy_requires_person",
                "Only a person can register, approve or revoke the tenancy of a data source.");
        tenancy(TenancyException.Reason.NOT_FOUND, HttpStatus.NOT_FOUND, "not_found", "Resource not found.");
        tenancy(TenancyException.Reason.SAME_PERSON, HttpStatus.CONFLICT, "tenancy_same_person",
                "The approver must be a person other than the one who registered.");
        tenancy(TenancyException.Reason.NOT_PENDING, HttpStatus.CONFLICT, "tenancy_not_pending",
                "Only a pending version newer than the active one can be approved; a revoked one stays revoked.");
        tenancy(TenancyException.Reason.MODEL_NOT_SUPPORTED, HttpStatus.UNPROCESSABLE_CONTENT,
                "tenancy_model_not_supported", "Only DEDICATED_DATABASE and DEDICATED_SCHEMAS can be registered.");
        tenancy(TenancyException.Reason.CAPTURE_MISMATCH, HttpStatus.UNPROCESSABLE_CONTENT,
                "tenancy_capture_mismatch",
                "The declared schemas or role do not match what the read-only role can use in the database.");
        tenancy(TenancyException.Reason.NO_CREDENTIALS, HttpStatus.CONFLICT, "data_source_not_configured",
                "No read-only connection is configured for this data source.");
        tenancy(TenancyException.Reason.TLS_REQUIRED, HttpStatus.UNPROCESSABLE_CONTENT, "tls_required",
                "The connection to the data source must use TLS with verify-full.");
        tenancy(TenancyException.Reason.TRANSPORT_EXCEPTION_EXPIRED, HttpStatus.UNPROCESSABLE_CONTENT,
                "transport_exception_expired", "The temporary transport exception of the data source has expired.");
        tenancy(TenancyException.Reason.TRANSPORT_EXCEPTION_MISMATCH, HttpStatus.CONFLICT,
                "transport_exception_mismatch",
                "The transport of the data source is not the one this tenancy version was captured with.");
        for (TenancyException.Reason reason : TenancyException.Reason.values()) {
            if (!TENANCY.containsKey(reason)) {
                throw new ExceptionInInitializerError("unmapped tenancy reason " + reason);
            }
        }
    }

    private static void tenancy(TenancyException.Reason reason, HttpStatus status, String apiCode, String detail) {
        TENANCY.put(reason, new Mapping(status, apiCode, detail));
    }

    private static void map(ErrorCode code, HttpStatus status, String apiCode, String detail) {
        EXECUTION.put(code, new Mapping(status, apiCode, detail));
    }

    /** Public mapping, documented in docs/API.md. */
    public static HttpStatus status(ErrorCode code) {
        return EXECUTION.get(code).status();
    }

    public static String apiCode(ErrorCode code) {
        return EXECUTION.get(code).code();
    }

    @ExceptionHandler(ExecutionException.class)
    ResponseEntity<Map<String, Object>> execution(ExecutionException e, HttpServletRequest request) {
        Mapping m = EXECUTION.get(e.code());
        if (m.status().is5xxServerError()) {
            log.warn("execution error {}: {}", e.code(), e.getMessage());
        } else {
            log.info("execution refused {}: {}", e.code(), e.getMessage());
        }
        return problem(m.status(), Problems.of(m.status(), m.code(), m.detail(), request));
    }

    /** A4-B9: the same problem, with the id of the run it concerns ({@code X-Run-Id}). */
    @ExceptionHandler(RunException.class)
    ResponseEntity<Map<String, Object>> run(RunException e, HttpServletRequest request) {
        Mapping m = EXECUTION.get(e.code());
        if (m.status().is5xxServerError()) {
            log.warn("run {} error {}: {}", e.runId(), e.code(), e.getMessage());
        } else {
            log.info("run {} answered {}: {}", e.runId(), e.code(), e.getMessage());
        }
        return ResponseEntity.status(m.status()).contentType(Problems.PROBLEM_JSON)
                .header(ReportRunController.RUN_ID_HEADER, e.runId().toString())
                .body(Problems.of(m.status(), m.code(), m.detail(), request));
    }

    /** A4-B9 (D-B9-6): refused registrations, approvals and revocations of tenancy. */
    @ExceptionHandler(TenancyException.class)
    ResponseEntity<Map<String, Object>> tenancy(TenancyException e, HttpServletRequest request) {
        Mapping m = TENANCY.get(e.reason());
        log.info("tenancy refused {}: {}", e.reason(), e.getMessage());
        return problem(m.status(), Problems.of(m.status(), m.code(), m.detail(), request));
    }

    /** Public mapping of tenancy refusals, documented in docs/API.md. */
    public static HttpStatus status(TenancyException.Reason reason) {
        return TENANCY.get(reason).status();
    }

    public static String apiCode(TenancyException.Reason reason) {
        return TENANCY.get(reason).code();
    }

    @ExceptionHandler(PlanNotExecutableException.class)
    ResponseEntity<Map<String, Object>> notExecutable(PlanNotExecutableException e, HttpServletRequest request) {
        log.info("plan not executable: {}", e.getMessage());
        HttpStatus status = HttpStatus.UNPROCESSABLE_CONTENT;
        List<Map<String, String>> violations = e.violations().stream()
                .map(v -> Map.of("code", v.code().name(), "path", v.path())).toList();
        return problem(status, Problems.with(Problems.of(status, "plan_not_executable",
                "The authorized plan cannot be executed safely by this version of Reports.", request),
                "violations", violations));
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidRequestException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return problem(status, Problems.with(Problems.of(status, "validation_failed", "The request is not valid.",
                request), "errors", List.of(Map.of("field", e.field(), "message", e.reason()))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e,
                                                     HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return problem(status, Problems.with(Problems.of(status, "validation_failed", "The request is not valid.",
                request), "errors", List.of(Map.of("field", e.getName(), "message", "invalid format"))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return problem(status, Problems.of(status, "malformed_request",
                "The request body is not valid JSON for this operation (unknown properties are rejected).",
                request));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<Map<String, Object>> forbidden(HttpServletRequest request) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        return problem(status, Problems.of(status, "forbidden", "Insufficient permissions.", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpServletRequest request) {
        HttpStatus status = HttpStatus.METHOD_NOT_ALLOWED;
        return problem(status, Problems.of(status, "method_not_allowed", "Method not allowed.", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpServletRequest request) {
        HttpStatus status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        return problem(status, Problems.of(status, "unsupported_media_type", "Use application/json.", request));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Map<String, Object>> notAcceptable(HttpServletRequest request) {
        HttpStatus status = HttpStatus.NOT_ACCEPTABLE;
        return problem(status, Problems.of(status, "not_acceptable", "Only application/json is produced.",
                request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noResource(HttpServletRequest request) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        return problem(status, Problems.of(status, "not_found", "Resource not found.", request));
    }

    /** A4-B10 (A4 proposal v3, section 5): a body sent to an operation that takes none. */
    @ExceptionHandler(RequestBodyNotAllowedException.class)
    ResponseEntity<Map<String, Object>> bodyNotAllowed(HttpServletRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return problem(status, Problems.of(status, "malformed_request", "This operation takes no request body.",
                request));
    }

    /**
     * A failure nobody anticipated. A4-B10 (D-B10-13): its message, which may carry values from a data source,
     * a document or another service, is withheld from the logs; its class and stack frames are kept.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected error", RedactedFailure.of(e));
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return problem(status, Problems.of(status, "internal_error", "Unexpected error.", request));
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(Problems.PROBLEM_JSON).body(body);
    }
}
