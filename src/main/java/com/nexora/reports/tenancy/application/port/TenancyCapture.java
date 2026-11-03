package com.nexora.reports.tenancy.application.port;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.tenancy.domain.DatabaseIdentity;

import java.util.List;

/**
 * Reads, from the database itself and with its read-only credentials, what a registration asserts: its identity,
 * the exact set of schemas the role can use and whether the session is encrypted.
 */
public interface TenancyCapture {

    Captured capture(DataSourceCredentials credentials);

    /**
     * @param encrypted      the session is encrypted ({@code pg_stat_ssl}); the certificate identity is checked by the
     *                       driver ({@code verify-full}), not by this flag
     * @param forbiddenRoles roles that make the read-only role inadmissible (superusers, predefined roles with access
     *                       beyond schema grants) among the role and its memberships; must be empty
     */
    record Captured(DatabaseIdentity identity, List<String> schemas, boolean encrypted, List<String> forbiddenRoles) {
        public Captured {
            schemas = List.copyOf(schemas);
            forbiddenRoles = List.copyOf(forbiddenRoles);
        }
    }
}
