package com.nexora.reports.execution.infrastructure;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.execution.application.port.CatalogReader;
import com.nexora.reports.execution.application.port.TenantScopeVerifier;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation.domain.SqlParameter.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only tenant scope check (A4-B4). It does not decide anything about tenancy (that is A4-B5): it records that
 * the executor called it, with which credentials, and what the transaction looked like at that moment (read-only,
 * isolation), and it can be told to refuse. It lives in test sources on purpose: production has no permissive
 * implementation of {@link TenantScopeVerifier}.
 */
final class ControlledTenantScope implements TenantScopeVerifier {

    private final ErrorCode refusal;
    final List<DataSourceCredentials> calls = new ArrayList<>();
    String readOnly;
    String isolation;

    private ControlledTenantScope(ErrorCode refusal) {
        this.refusal = refusal;
    }

    /** Accepts, after reading the transaction settings through the catalog reader the executor provides. */
    static ControlledTenantScope accepting() {
        return new ControlledTenantScope(null);
    }

    /** Refuses with the given code, as a tenant scope check of A4-B5 would. */
    static ControlledTenantScope refusing(ErrorCode code) {
        return new ControlledTenantScope(code);
    }

    @Override
    public void verify(DataSourceCredentials credentials, CatalogReader catalog) {
        calls.add(credentials);
        readOnly = (String) catalog.query("SELECT current_setting(?)",
                List.of(new SqlParameter(SqlType.TEXT, "transaction_read_only"))).getFirst().getFirst();
        isolation = (String) catalog.query("SELECT current_setting(?)",
                List.of(new SqlParameter(SqlType.TEXT, "transaction_isolation"))).getFirst().getFirst();
        if (refusal != null) {
            throw new ExecutionException(refusal, "refused by the controlled tenant scope check");
        }
    }
}
