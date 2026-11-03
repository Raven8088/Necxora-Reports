package com.nexora.reports.execution.application.port;

import com.nexora.reports.credentials.domain.DataSourceCredentials;

/**
 * Tenant scope check of a {@code /2} execution (P-1, A4 proposal v3 section 4 bis.8), run by the executor inside the
 * read transaction, after the session checks and before the schema checks and any business data are read. The
 * implementation belongs to A4-B5; A4-B4 only makes it mandatory. There is deliberately no permissive implementation
 * in production code.
 * <p>
 * To refuse, it throws {@link com.nexora.reports.execution.domain.ExecutionException}; the executor rolls back and
 * reads nothing. It must only read the catalog, never business data.
 */
public interface TenantScopeVerifier {

    void verify(DataSourceCredentials credentials, CatalogReader catalog);
}
