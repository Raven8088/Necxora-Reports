package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.ExecutionRequestV2;
import com.nexora.reports.execution.domain.RunBudget;
import com.nexora.reports.execution.domain.SnapshotRead;

import java.util.function.IntConsumer;

/**
 * Test-only access, from other test packages, to the executor's callback before each dataset query, so that a test
 * can prove that a refused execution never reached a data query. It adds no behaviour.
 */
public final class ExecutorTestAccess {

    private ExecutorTestAccess() {
    }

    public static SnapshotRead executeAll(PostgresReadOnlyExecutor executor, DataSourceCredentials credentials,
                                          ExecutionRequestV2 request, RunBudget budget,
                                          TenantScopeVerifier tenantScope, IntConsumer beforeQuery) {
        return executor.executeAll(credentials, request, budget, tenantScope, beforeQuery);
    }
}
