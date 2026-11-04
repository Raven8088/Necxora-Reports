-- Nexora Reports EX-TLS-1, correction of V5 (V5 is kept unchanged: it may already be applied in a persistent
-- database, and Flyway refuses an applied migration whose checksum changed).
-- V5's CHECK let a partial transport exception through: PostgreSQL treats a CHECK whose expression is NULL as
-- satisfied, and with a NULL column the pattern tests of V5 evaluate to NULL instead of false (for example
-- (NULL, now(), NULL) or ('EX-TLS-1', now(), NULL) were accepted). The second branch now requires the three columns
-- explicitly, so the expression is never NULL: either none is set or all three are set and well-formed.
-- If a stored row already violates the rule, this migration fails and Reports does not start (fail-closed): such a
-- row must be examined by a person, never repaired automatically.

ALTER TABLE tenancy_registration DROP CONSTRAINT tenancy_registration_transport_ck;

ALTER TABLE tenancy_registration ADD CONSTRAINT tenancy_registration_transport_ck CHECK (
    (transport_exception_id IS NULL AND transport_exception_expires_at IS NULL
        AND transport_exception_binding IS NULL)
    OR (transport_exception_id IS NOT NULL AND transport_exception_expires_at IS NOT NULL
        AND transport_exception_binding IS NOT NULL
        AND transport_exception_id ~ '^[A-Z0-9][A-Z0-9-]{0,31}$'
        AND transport_exception_binding ~ '^[0-9a-f]{64}$'));
