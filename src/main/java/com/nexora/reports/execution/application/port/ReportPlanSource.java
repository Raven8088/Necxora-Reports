package com.nexora.reports.execution.application.port;

import com.nexora.reports.plan2.domain.ReportPlanValidity;
import com.nexora.reports.plan2.domain.SemanticDataSource;

import java.util.Map;
import java.util.UUID;

/**
 * A4-B9 (D-B9-2): the documents of a report plan compiled by Nexora Semantic (A3), always read with the service
 * identity of {@code tenantId} (role REPORTS_CLIENT). Documents are returned as the plain trees Reports validates
 * itself (B2); nothing here is trusted before that validation.
 * <p>
 * Implementations throw {@link com.nexora.reports.execution.domain.ExecutionException} with
 * {@code DATA_SOURCE_NOT_FOUND}, {@code PLAN_NOT_FOUND} (report plan), {@code DATA_SOURCE_NOT_EXECUTABLE},
 * {@code REQUESTER_NOT_ALLOWED}, {@code SEMANTIC_UNAVAILABLE}, {@code SEMANTIC_CONTRACT_VIOLATION} or
 * {@code SERVICE_IDENTITY_INVALID}; {@link #validity} throws {@code PLAN_VALIDITY_UNCONFIRMED} when the validity
 * cannot be confirmed, which is never taken as CURRENT. An unreadable document throws
 * {@link com.nexora.reports.plan2.domain.PlanV2Exception} ({@code UNREADABLE}).
 */
public interface ReportPlanSource {

    SemanticDataSource dataSource(UUID tenantId, UUID dataSourceId);

    Map<String, Object> reportPlan(UUID tenantId, UUID dataSourceId, UUID reportPlanId);

    Map<String, Object> spec(UUID tenantId, UUID dataSourceId, UUID reportPlanId);

    Map<String, Object> logicalPlan(UUID tenantId, UUID dataSourceId, UUID reportPlanId, UUID planId);

    ReportPlanValidity validity(UUID tenantId, UUID dataSourceId, UUID reportPlanId);
}
