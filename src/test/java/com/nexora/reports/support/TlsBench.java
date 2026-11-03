package com.nexora.reports.support;

import com.nexora.reports.plan2.ContractFixtures;

import java.nio.charset.StandardCharsets;

/**
 * A4-B9: the synthetic bench of A0 (its verified copy) loaded into the PostgreSQL <b>with real TLS</b> of
 * {@link TenantDatabase}, so that a report run goes through everything production requires: {@code verify-full},
 * a registered and approved tenancy and the tenant scope check. The bench is read through its own read-only role;
 * {@code public} is closed to it, so the schemas it can use are exactly the bench's two schemas
 * ({@code DEDICATED_SCHEMAS}).
 */
public final class TlsBench {

    public static final String DATABASE = BenchDatabase.DATABASE;
    public static final String READER = BenchDatabase.READER;
    public static final String SCHEMA = "wellnode_sintetico";
    public static final String VOLUME_SCHEMA = "wellnode_sintetico_volumen";

    private static boolean ready;

    private TlsBench() {
    }

    /** Creates the bench database once per JVM. */
    public static synchronized void ensure() {
        if (ready) {
            return;
        }
        TenantDatabase.exec("nexora_tenancy", "CREATE DATABASE " + DATABASE);
        String root = "nexora-testbench/reportes/sql/";
        TenantDatabase.exec(DATABASE, "REVOKE ALL ON SCHEMA public FROM PUBLIC",
                text(root + "01-esquema.sql"), text(root + "02-datos.sql"), text(root + "03-rol-lector.sql"),
                "ALTER ROLE " + READER + " PASSWORD '" + TenantDatabase.PASSWORD + "'");
        ready = true;
    }

    private static String text(String resource) {
        return new String(ContractFixtures.bytes(resource), StandardCharsets.UTF_8);
    }
}
