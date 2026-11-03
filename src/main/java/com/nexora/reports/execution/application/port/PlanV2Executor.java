package com.nexora.reports.execution.application.port;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;

/**
 * Runs every dataset of one validated {@code /2} report plan (A4-B4) in one connection and one REPEATABLE READ,
 * read-only transaction that is always rolled back: session checks, the mandatory tenant scope check, the live
 * schema of every query, the snapshot, then the queries in order with typed values, truncation and limits.
 * <p>
 * Throws {@link com.nexora.reports.execution.domain.ExecutionException} with a stable code; nothing is read when a
 * check fails. Throws {@link IllegalArgumentException} before connecting if the credentials are not those of the
 * request's tenant and data source.
 *
 * @param tenantScope mandatory: there is no execution without a tenant scope check (A4-B5)
 */
public interface PlanV2Executor {

    SnapshotRead executeAll(DataSourceCredentials credentials, ExecutionRequestV2 request, RunBudget budget,
                            TenantScopeVerifier tenantScope);
}
