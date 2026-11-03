package com.nexora.reports.security;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Reports permissions (Spring Security authorities) and the roles that grant them. */
public enum Permission {

    /** Execute an authorized logical plan of the caller's tenant. */
    EXECUTE("reports:execute"),
    /**
     * A4-B9 (D-B9-5): register, approve, revoke and list the tenancy of the caller's data sources. Only a person ever
     * holds it ({@link RequesterJwtConverter}); approving also requires a person other than the registrant.
     */
    TENANCY_MANAGE("reports:tenancy:manage");

    public static final String EXECUTE_AUTHORITY = "reports:execute";
    public static final String REQUIRE_EXECUTE = "hasAuthority('" + EXECUTE_AUTHORITY + "')";
    public static final String TENANCY_MANAGE_AUTHORITY = "reports:tenancy:manage";
    public static final String REQUIRE_TENANCY_MANAGE = "hasAuthority('" + TENANCY_MANAGE_AUTHORITY + "')";
    /**
     * A4-B10 (D-B10-3): any authenticated principal of a tenant, with no specific permission (the capabilities
     * descriptor). Every authenticated principal of Reports has a validated tenant ({@link RequesterJwtConverter}).
     */
    public static final String REQUIRE_TENANT_PRINCIPAL = "isAuthenticated()";

    private final String authority;

    Permission(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    /** Roles carried by access tokens. Unknown role names (identity-provider defaults, Semantic roles) are ignored. */
    public enum Role {
        REPORT_RUNNER(EnumSet.of(EXECUTE)),
        /** A4-B9: tenancy of data sources; effective for persons only. */
        REPORTS_TENANCY_ADMIN(EnumSet.of(TENANCY_MANAGE));

        private final Set<Permission> permissions;

        Role(Set<Permission> permissions) {
            this.permissions = permissions;
        }

        public Set<Permission> permissions() {
            return EnumSet.copyOf(permissions);
        }

        public static Optional<Role> fromName(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
