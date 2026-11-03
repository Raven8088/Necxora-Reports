package com.nexora.reports.execution.application.port;

import com.nexora.reports.translation.domain.SqlParameter;

import java.util.List;

/**
 * Parameterized catalog queries on the connection and transaction of the running execution, for
 * {@link TenantScopeVerifier}. The text must contain no literal values (it is checked like translated SQL); values
 * are parameters. Rows are returned as JDBC objects.
 */
public interface CatalogReader {

    List<List<Object>> query(String sql, List<SqlParameter> parameters);
}
