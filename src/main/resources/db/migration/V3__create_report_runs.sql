-- Nexora Reports A4-B8: run registry, artifacts and their content (D-A4-3, D-A4-6, D-B8-1 to D-B8-8).
-- Ids, fingerprints, codes, counts, sizes and times only; business data exists only inside the artifact content.
-- Every row carries its tenant; composite keys keep a run, its outcome, its artifact and its content in one tenant
-- and source. Nothing is ever updated: states are derived from the database clock (D-B8-3).

-- A started run, committed before any data is read so that it stays traceable.
CREATE TABLE report_run (
    id             uuid         PRIMARY KEY,
    tenant_id      uuid         NOT NULL,
    data_source_id uuid         NOT NULL,
    report_plan_id uuid         NOT NULL,
    owner_subject  varchar(255) NOT NULL CHECK (length(owner_subject) > 0),
    owner_type     varchar(16)  NOT NULL CHECK (owner_type IN ('HUMAN', 'SERVICE')),
    started_at     timestamptz  NOT NULL,
    deadline_at    timestamptz  NOT NULL,
    CONSTRAINT report_run_deadline_ck CHECK (deadline_at > started_at),
    CONSTRAINT report_run_tenant_uk UNIQUE (id, tenant_id),
    CONSTRAINT report_run_scope_uk UNIQUE (id, tenant_id, data_source_id)
);

-- How a run ended: exactly once (primary key), RUN-R1 extended to every real state.
CREATE TABLE report_run_outcome (
    run_id                  uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL,
    data_source_id          uuid        NOT NULL,
    kind                    varchar(16) NOT NULL
        CHECK (kind IN ('COMPLETED', 'PARTIAL', 'REFUSED', 'FAILED', 'INTERRUPTED')),
    report_plan_fingerprint char(64)    CHECK (report_plan_fingerprint ~ '^[0-9a-f]{64}$'),
    spec_fingerprint        char(64)    CHECK (spec_fingerprint ~ '^[0-9a-f]{64}$'),
    refusal_code            varchar(63) CHECK (refusal_code ~ '^[a-z][a-z0-9_]{0,62}$'),
    failure_code            varchar(32) CHECK (failure_code IN ('render_failed', 'storage_failed', 'internal_error')),
    snapshot                varchar(4096) CHECK (snapshot ~ '^\d+:\d+:(\d+(,\d+)*)?$'),
    datasets                jsonb       NOT NULL CHECK (jsonb_typeof(datasets) = 'array'),
    warnings                jsonb       NOT NULL CHECK (jsonb_typeof(warnings) = 'array'),
    finished_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT report_run_outcome_run_fk FOREIGN KEY (run_id, tenant_id, data_source_id)
        REFERENCES report_run (id, tenant_id, data_source_id),
    CONSTRAINT report_run_outcome_scope_uk UNIQUE (run_id, tenant_id, data_source_id),
    CONSTRAINT report_run_outcome_codes_ck CHECK (
        (kind IN ('COMPLETED', 'PARTIAL') AND refusal_code IS NULL AND failure_code IS NULL
            AND report_plan_fingerprint IS NOT NULL AND spec_fingerprint IS NOT NULL AND snapshot IS NOT NULL)
        OR (kind = 'REFUSED' AND refusal_code IS NOT NULL AND failure_code IS NULL)
        OR (kind = 'FAILED' AND failure_code IS NOT NULL AND refusal_code IS NULL)
        OR (kind = 'INTERRUPTED' AND refusal_code IS NULL AND failure_code IS NULL)),
    CONSTRAINT report_run_outcome_partial_ck CHECK (
        kind NOT IN ('COMPLETED', 'PARTIAL')
        OR (kind = 'PARTIAL') = jsonb_path_exists(datasets, '$[*] ? (@.truncated == true)'))
);

-- The metadata of an artifact (nexora-report-artifact/1). Only a COMPLETED or PARTIAL outcome has one.
CREATE TABLE report_artifact (
    id             uuid        PRIMARY KEY,
    run_id         uuid        NOT NULL UNIQUE,
    tenant_id      uuid        NOT NULL,
    data_source_id uuid        NOT NULL,
    media_type     varchar(80) NOT NULL
        CHECK (media_type = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
    excel_contract varchar(32) NOT NULL CHECK (excel_contract = 'nexora-excel-report/1'),
    sha256         char(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes     bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    sheets         text[]      NOT NULL CHECK (cardinality(sheets) >= 2),
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    CONSTRAINT report_artifact_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT report_artifact_outcome_fk FOREIGN KEY (run_id, tenant_id, data_source_id)
        REFERENCES report_run_outcome (run_id, tenant_id, data_source_id),
    CONSTRAINT report_artifact_tenant_uk UNIQUE (id, tenant_id)
);

-- The bytes, apart (D-B8-1). Inserted once, checked against the metadata, deleted only once expired.
CREATE TABLE report_artifact_content (
    artifact_id uuid  PRIMARY KEY,
    tenant_id   uuid  NOT NULL,
    content     bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 20971520),
    CONSTRAINT report_artifact_content_artifact_fk FOREIGN KEY (artifact_id, tenant_id)
        REFERENCES report_artifact (id, tenant_id)
);

-- What happened to runs and artifacts in the store. Never content, filter values or questions.
CREATE TABLE report_run_event (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    run_id      uuid        NOT NULL,
    artifact_id uuid,
    kind        varchar(32) NOT NULL CHECK (kind IN ('RUN_STARTED', 'RUN_FINISHED', 'RUN_INTERRUPTED',
                    'ARTIFACT_STORED', 'ARTIFACT_RETRIEVED', 'ARTIFACT_INTEGRITY_FAILED', 'ARTIFACT_PURGED')),
    detail      varchar(63) CHECK (detail ~ '^[A-Za-z][A-Za-z0-9_]{0,62}$'),
    sha256      char(64)    CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes  bigint      CHECK (size_bytes >= 1),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT report_run_event_run_fk FOREIGN KEY (run_id, tenant_id) REFERENCES report_run (id, tenant_id),
    CONSTRAINT report_run_event_artifact_fk FOREIGN KEY (artifact_id, tenant_id)
        REFERENCES report_artifact (id, tenant_id)
);

CREATE INDEX report_run_owner_idx ON report_run (tenant_id, data_source_id, owner_subject, started_at DESC);
CREATE INDEX report_run_deadline_idx ON report_run (deadline_at);
CREATE INDEX report_artifact_quota_idx ON report_artifact (tenant_id, expires_at);
CREATE INDEX report_artifact_expiry_idx ON report_artifact (expires_at);
CREATE INDEX report_run_event_run_idx ON report_run_event (tenant_id, run_id, recorded_at);

-- Metadata and events are append-only: rows can be inserted and read, never changed or removed.
CREATE FUNCTION report_run_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER report_run_no_update_delete BEFORE UPDATE OR DELETE ON report_run
    FOR EACH ROW EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_run_no_truncate BEFORE TRUNCATE ON report_run
    FOR EACH STATEMENT EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_run_outcome_no_update_delete BEFORE UPDATE OR DELETE ON report_run_outcome
    FOR EACH ROW EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_run_outcome_no_truncate BEFORE TRUNCATE ON report_run_outcome
    FOR EACH STATEMENT EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_artifact_no_update_delete BEFORE UPDATE OR DELETE ON report_artifact
    FOR EACH ROW EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_artifact_no_truncate BEFORE TRUNCATE ON report_artifact
    FOR EACH STATEMENT EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_run_event_no_update_delete BEFORE UPDATE OR DELETE ON report_run_event
    FOR EACH ROW EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_run_event_no_truncate BEFORE TRUNCATE ON report_run_event
    FOR EACH STATEMENT EXECUTE FUNCTION report_run_append_only();

-- Content: never updated or truncated; deleted only once its artifact has expired (P-3).
CREATE TRIGGER report_artifact_content_no_update BEFORE UPDATE ON report_artifact_content
    FOR EACH ROW EXECUTE FUNCTION report_run_append_only();
CREATE TRIGGER report_artifact_content_no_truncate BEFORE TRUNCATE ON report_artifact_content
    FOR EACH STATEMENT EXECUTE FUNCTION report_run_append_only();

CREATE FUNCTION report_artifact_content_expired_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM report_artifact a
                WHERE a.id = OLD.artifact_id AND a.tenant_id = OLD.tenant_id
                  AND a.expires_at > clock_timestamp()) THEN
        RAISE EXCEPTION 'only the content of an expired artifact can be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER report_artifact_content_delete_expired_only BEFORE DELETE ON report_artifact_content
    FOR EACH ROW EXECUTE FUNCTION report_artifact_content_expired_only();

-- Adjustment 2: the bytes must be exactly those described by their artifact, size and SHA-256.
CREATE FUNCTION report_artifact_content_matches() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    expected_size bigint;
    expected_sha  char(64);
BEGIN
    SELECT a.size_bytes, a.sha256 INTO expected_size, expected_sha
      FROM report_artifact a WHERE a.id = NEW.artifact_id AND a.tenant_id = NEW.tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'content without its artifact' USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF octet_length(NEW.content) <> expected_size
            OR encode(sha256(NEW.content), 'hex') <> expected_sha THEN
        RAISE EXCEPTION 'content does not match its artifact' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER report_artifact_content_matches_check BEFORE INSERT ON report_artifact_content
    FOR EACH ROW EXECUTE FUNCTION report_artifact_content_matches();

-- Only a COMPLETED or PARTIAL outcome can have an artifact.
CREATE FUNCTION report_artifact_delivered_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM report_run_outcome o
                    WHERE o.run_id = NEW.run_id AND o.tenant_id = NEW.tenant_id
                      AND o.kind IN ('COMPLETED', 'PARTIAL')) THEN
        RAISE EXCEPTION 'only a COMPLETED or PARTIAL run has an artifact' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER report_artifact_delivered_only_check BEFORE INSERT ON report_artifact
    FOR EACH ROW EXECUTE FUNCTION report_artifact_delivered_only();

-- At commit: a COMPLETED or PARTIAL outcome has its artifact, and a new artifact has its content.
CREATE FUNCTION report_run_outcome_has_artifact() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.kind IN ('COMPLETED', 'PARTIAL') AND NOT EXISTS (
            SELECT 1 FROM report_artifact a WHERE a.run_id = NEW.run_id AND a.tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'a COMPLETED or PARTIAL run needs its artifact' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER report_run_outcome_has_artifact_check AFTER INSERT ON report_run_outcome
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION report_run_outcome_has_artifact();

CREATE FUNCTION report_artifact_has_content() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM report_artifact_content c
                    WHERE c.artifact_id = NEW.id AND c.tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'a new artifact needs its content' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER report_artifact_has_content_check AFTER INSERT ON report_artifact
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION report_artifact_has_content();

-- When the application uses its own restricted role (recommended): SELECT and INSERT everywhere, DELETE only on the
-- content (and the trigger above allows it only for expired artifacts). No UPDATE anywhere.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') AND '${appRole}' <> current_user THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT ON report_run, report_run_outcome, report_artifact, report_run_event'
                       ' TO %I', '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON report_artifact_content TO %I', '${appRole}');
    END IF;
END
$$;
