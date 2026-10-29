package com.nexora.reports.security;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Reports permissions (Spring Security authorities) and the roles that grant them. */
public enum Permission {

    /** Execute an authorized logical plan of the caller's tenant. */
    EXECUTE("reports:execute");

    public static final String EXECUTE_AUTHORITY = "reports:execute";
    public static final String REQUIRE_EXECUTE = "hasAuthority('" + EXECUTE_AUTHORITY + "')";

    private final String authority;

    Permission(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    /** Roles carried by access tokens. Unknown role names (identity-provider defaults, Semantic roles) are ignored. */
    public enum Role {
        REPORT_RUNNER(EnumSet.of(EXECUTE));

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
