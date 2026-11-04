-- Nexora Reports EX-TLS-1: the transport exception under which a tenancy registration was captured.
-- NULL in the three columns = captured with sslmode=verify-full (every registration before EX-TLS-1 and every one of a
-- normal build). The three are set together or not at all. No credentials; the binding is the SHA-256 of the
-- authorized destination (no secret in it). The registry stays append-only (triggers of V2 unchanged).

ALTER TABLE tenancy_registration
    ADD COLUMN transport_exception_id         varchar(32),
    ADD COLUMN transport_exception_expires_at timestamptz,
    ADD COLUMN transport_exception_binding    char(64),
    ADD CONSTRAINT tenancy_registration_transport_ck CHECK (
        (transport_exception_id IS NULL AND transport_exception_expires_at IS NULL
            AND transport_exception_binding IS NULL)
        OR (transport_exception_id ~ '^[A-Z0-9][A-Z0-9-]{0,31}$' AND transport_exception_expires_at IS NOT NULL
            AND transport_exception_binding ~ '^[0-9a-f]{64}$'));
