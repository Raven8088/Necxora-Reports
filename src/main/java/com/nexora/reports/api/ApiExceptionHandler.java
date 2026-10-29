package com.nexora.reports.api;

import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.domain.PlanNotExecutableException;
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
        for (ErrorCode code : ErrorCode.values()) {
            if (!EXECUTION.containsKey(code)) {
                throw new ExceptionInInitializerError("unmapped error code " + code);
            }
        }
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

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected error", e);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return problem(status, Problems.of(status, "internal_error", "Unexpected error.", request));
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(Problems.PROBLEM_JSON).body(body);
    }
}
