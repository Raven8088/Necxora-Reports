package com.nexora.reports.execution.application.port;

import com.nexora.reports.plan.domain.DataSourceDescriptor;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.StoredPlan;

import java.util.UUID;

/**
 * Authenticated access to Nexora Semantic with the service identity of {@code tenantId} (role REPORTS_CLIENT).
 * Implementations throw {@link com.nexora.reports.execution.domain.ExecutionException} with
 * {@code DATA_SOURCE_NOT_FOUND}, {@code PLAN_NOT_FOUND}, {@code DATA_SOURCE_NOT_EXECUTABLE},
 * {@code SEMANTIC_UNAVAILABLE}, {@code SEMANTIC_CONTRACT_VIOLATION} or {@code SERVICE_IDENTITY_INVALID}, and
 * {@link com.nexora.reports.plan.domain.PlanNotExecutableException} when the plan document cannot be read as
 * {@code nexora-logical-plan/1}.
 */
public interface PlanSource {

    DataSourceDescriptor dataSource(UUID tenantId, UUID dataSourceId);

    StoredPlan plan(UUID tenantId, UUID dataSourceId, UUID planId);

    PlanValidity validity(UUID tenantId, UUID dataSourceId, UUID planId);
}
