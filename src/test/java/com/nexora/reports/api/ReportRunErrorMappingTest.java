package com.nexora.reports.api;

import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.tenancy.domain.TenancyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B9 (D-B9-10): every error code and every tenancy refusal has its documented HTTP answer (docs/API.md). The
 * handler also refuses to load with an unmapped code, so a new code cannot reach clients undocumented.
 */
class ReportRunErrorMappingTest {

    @Test
    void everyCodeAndEveryTenancyReasonIsMapped() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(ApiExceptionHandler.status(code)).as(code.name()).isNotNull();
            assertThat(ApiExceptionHandler.apiCode(code)).as(code.name()).matches("[a-z][a-z0-9_]*");
        }
        for (TenancyException.Reason reason : TenancyException.Reason.values()) {
            assertThat(ApiExceptionHandler.status(reason)).as(reason.name()).isNotNull();
            assertThat(ApiExceptionHandler.apiCode(reason)).as(reason.name()).matches("[a-z][a-z0-9_]*");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RUN_NOT_FOUND, NOT_FOUND, not_found",
            "RUN_IN_PROGRESS, CONFLICT, run_in_progress",
            "RUN_RECORDED_AS_FAILED, CONFLICT, run_failed",
            "RUN_INTERRUPTED, CONFLICT, run_interrupted",
            "RUN_FAILED, INTERNAL_SERVER_ERROR, run_failed",
            "REPORT_PLAN_INVALID, UNPROCESSABLE_CONTENT, report_plan_invalid",
            "ARTIFACT_GONE, GONE, artifact_gone",
            "ARTIFACT_INTEGRITY_FAILED, INTERNAL_SERVER_ERROR, artifact_integrity_failed",
            "TOO_MANY_DOWNLOADS, TOO_MANY_REQUESTS, too_many_downloads",
            "RUN_STORAGE_UNAVAILABLE, SERVICE_UNAVAILABLE, run_storage_unavailable",
            "TENANT_SCOPE_UNVERIFIED, CONFLICT, tenant_scope_unverified",
            "DATA_SOURCE_NOT_CONFIGURED, CONFLICT, data_source_not_configured",
            "DATA_SOURCE_TARGET_NOT_ALLOWED, INTERNAL_SERVER_ERROR, data_source_target_not_allowed",
            "DATA_SOURCE_CONFIGURATION_CHANGED, CONFLICT, data_source_configuration_changed"})
    void runErrorsAreAnsweredAsDocumented(String code, String status, String apiCode) {
        assertThat(ApiExceptionHandler.status(ErrorCode.valueOf(code))).isEqualTo(HttpStatus.valueOf(status));
        assertThat(ApiExceptionHandler.apiCode(ErrorCode.valueOf(code))).isEqualTo(apiCode);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "NOT_A_PERSON, FORBIDDEN, tenancy_requires_person",
            "NOT_FOUND, NOT_FOUND, not_found",
            "SAME_PERSON, CONFLICT, tenancy_same_person",
            "NOT_PENDING, CONFLICT, tenancy_not_pending",
            "MODEL_NOT_SUPPORTED, UNPROCESSABLE_CONTENT, tenancy_model_not_supported",
            "CAPTURE_MISMATCH, UNPROCESSABLE_CONTENT, tenancy_capture_mismatch",
            "NO_CREDENTIALS, CONFLICT, data_source_not_configured",
            "TLS_REQUIRED, UNPROCESSABLE_CONTENT, tls_required"})
    void tenancyRefusalsAreAnsweredAsDocumented(String reason, String status, String apiCode) {
        TenancyException.Reason r = TenancyException.Reason.valueOf(reason);
        assertThat(ApiExceptionHandler.status(r)).isEqualTo(HttpStatus.valueOf(status));
        assertThat(ApiExceptionHandler.apiCode(r)).isEqualTo(apiCode);
    }
}
