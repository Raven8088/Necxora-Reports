-- Nexora Reports A4-B5: tenancy registry (P-1, P-1b, D-B5-1, D-B5-2).
-- Immutable versions of what each (tenant, data source) is registered as, and the human decisions on them.
-- No credentials, no business data. Only DEDICATED_DATABASE and DEDICATED_SCHEMAS can be stored.

CREATE TABLE tenancy_registration (
    id                uuid         PRIMARY KEY,
    tenant_id         uuid         NOT NULL,
    data_source_id    uuid         NOT NULL,
    version           integer      NOT NULL CHECK (version > 0),
    model             varchar(32)  NOT NULL CHECK (model IN ('DEDICATED_DATABASE', 'DEDICATED_SCHEMAS')),
    host              varchar(255) NOT NULL,
    port              integer      NOT NULL CHECK (port BETWEEN 1 AND 65535),
    database_name     varchar(63)  NOT NULL,
    database_oid      bigint       NOT NULL CHECK (database_oid > 0),
    system_identifier varchar(32)  NOT NULL,
    role_name         varchar(63)  NOT NULL,
    schemas           text[]       NOT NULL CHECK (cardinality(schemas) > 0),
    registered_by     varchar(255) NOT NULL,
    registered_at     timestamptz  NOT NULL,
    fingerprint       char(64)     NOT NULL,
    recorded_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT tenancy_registration_version_uk UNIQUE (tenant_id, data_source_id, version)
);

CREATE TABLE tenancy_decision (
    id              uuid         PRIMARY KEY,
    registration_id uuid         NOT NULL REFERENCES tenancy_registration (id),
    kind            varchar(16)  NOT NULL CHECK (kind IN ('APPROVED', 'REVOKED')),
    decided_by      varchar(255) NOT NULL,
    decided_at      timestamptz  NOT NULL,
    recorded_at     timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT tenancy_decision_once_uk UNIQUE (registration_id, kind)
);

CREATE INDEX tenancy_registration_source_idx ON tenancy_registration (tenant_id, data_source_id, version);

-- Append-only: rows can be inserted and read, never changed or removed.
CREATE FUNCTION tenancy_registry_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER tenancy_registration_no_update_delete
    BEFORE UPDATE OR DELETE ON tenancy_registration
    FOR EACH ROW EXECUTE FUNCTION tenancy_registry_append_only();
CREATE TRIGGER tenancy_registration_no_truncate
    BEFORE TRUNCATE ON tenancy_registration
    FOR EACH STATEMENT EXECUTE FUNCTION tenancy_registry_append_only();
CREATE TRIGGER tenancy_decision_no_update_delete
    BEFORE UPDATE OR DELETE ON tenancy_decision
    FOR EACH ROW EXECUTE FUNCTION tenancy_registry_append_only();
CREATE TRIGGER tenancy_decision_no_truncate
    BEFORE TRUNCATE ON tenancy_decision
    FOR EACH STATEMENT EXECUTE FUNCTION tenancy_registry_append_only();

-- Defense in depth for D-B5-2: the person who registered a version can never approve it.
CREATE FUNCTION tenancy_decision_four_eyes() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.kind = 'APPROVED' AND EXISTS (
            SELECT 1 FROM tenancy_registration r
             WHERE r.id = NEW.registration_id AND r.registered_by = NEW.decided_by) THEN
        RAISE EXCEPTION 'the registrant cannot approve a tenancy registration';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenancy_decision_four_eyes_check
    BEFORE INSERT ON tenancy_decision
    FOR EACH ROW EXECUTE FUNCTION tenancy_decision_four_eyes();

-- When the application uses its own restricted role (recommended), it only gets SELECT and INSERT.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') AND '${appRole}' <> current_user THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT ON tenancy_registration, tenancy_decision TO %I', '${appRole}');
    END IF;
END
$$;
