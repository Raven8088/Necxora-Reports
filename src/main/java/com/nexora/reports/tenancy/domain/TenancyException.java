package com.nexora.reports.tenancy.domain;

import java.util.Objects;

/**
 * A refused registration, approval or revocation of tenancy. The message is internal and never contains
 * credentials, values or connection details.
 */
public class TenancyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** Only a person (principal HUMAN) may register, approve or revoke. */
        NOT_A_PERSON,
        /** The registration belongs to another tenant (or does not exist for this one). */
        NOT_FOUND,
        /** The approver is the person who registered the version. */
        SAME_PERSON,
        /** Only a pending version, newer than the active one, can be approved. */
        NOT_PENDING,
        /** {@code SHARED_ROWS} cannot be registered in A4. */
        MODEL_NOT_SUPPORTED,
        /** The declared schemas are not exactly those the read-only role can use. */
        CAPTURE_MISMATCH,
        /** There are no read-only credentials for (tenant, data source). */
        NO_CREDENTIALS,
        /** The connection does not use TLS with {@code verify-full}. */
        TLS_REQUIRED,
        /** EX-TLS-1: the transport exception of the source has expired. */
        TRANSPORT_EXCEPTION_EXPIRED,
        /** EX-TLS-1: the transport of the configured source is not the one the version was captured with. */
        TRANSPORT_EXCEPTION_MISMATCH
    }

    private final Reason reason;

    public TenancyException(Reason reason, String internalMessage) {
        super(internalMessage);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
