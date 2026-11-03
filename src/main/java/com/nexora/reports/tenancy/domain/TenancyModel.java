package com.nexora.reports.tenancy.domain;

/**
 * How a data source is dedicated to its tenant (P-1, A4 proposal v3 section 4 bis.8). Only the two dedicated models
 * can be registered and executed; {@link #SHARED_ROWS} exists only so that a declaration of it is recognised and
 * refused: no check of A4 can prove which rows belong to whom (that is the future row-level security stage, D-7).
 */
public enum TenancyModel {
    /** The whole database holds data of the tenant only (provisioned so). */
    DEDICATED_DATABASE,
    /** A shared database in which the tenant owns its own schemas. */
    DEDICATED_SCHEMAS,
    /** The same tables hold rows of several tenants: always refused in A4. */
    SHARED_ROWS;

    public boolean executable() {
        return this == DEDICATED_DATABASE || this == DEDICATED_SCHEMAS;
    }
}
