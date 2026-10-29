-- Nexora Reports R1: technical audit of report executions.
-- Identifiers, classifications, counts and timings only: no filter values, SQL, result rows or credentials.

CREATE TABLE report_execution_audit (
    execution_id      uuid         PRIMARY KEY,
    tenant_id         uuid         NOT NULL,
    data_source_id    uuid         NOT NULL,
    plan_id           uuid         NOT NULL,
    requester_subject varchar(255) NOT NULL,
    requester_type    varchar(16)  NOT NULL CHECK (requester_type IN ('HUMAN', 'SERVICE', 'AI')),
    plan_fingerprint  varchar(128),
    validity_status   varchar(32)  CHECK (validity_status IN ('CURRENT', 'CURRENT_WITH_CHANGES', 'STALE')),
    outcome           varchar(16)  NOT NULL CHECK (outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')),
    error_code        varchar(64),
    row_count         integer      CHECK (row_count >= 0),
    truncated         boolean      NOT NULL,
    duration_ms       bigint       NOT NULL CHECK (duration_ms >= 0),
    started_at        timestamptz  NOT NULL,
    recorded_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT report_execution_audit_outcome_ck CHECK (
        (outcome = 'SUCCEEDED' AND error_code IS NULL AND row_count IS NOT NULL)
        OR (outcome <> 'SUCCEEDED' AND error_code IS NOT NULL AND row_count IS NULL))
);

CREATE INDEX report_execution_audit_tenant_idx ON report_execution_audit (tenant_id, recorded_at DESC);
CREATE INDEX report_execution_audit_plan_idx ON report_execution_audit (tenant_id, data_source_id, plan_id);

-- Append-only: rows can be inserted and read, never changed or removed.
CREATE FUNCTION report_execution_audit_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'report_execution_audit is append-only';
END;
$$;

CREATE TRIGGER report_execution_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON report_execution_audit
    FOR EACH ROW EXECUTE FUNCTION report_execution_audit_append_only();

CREATE TRIGGER report_execution_audit_no_truncate
    BEFORE TRUNCATE ON report_execution_audit
    FOR EACH STATEMENT EXECUTE FUNCTION report_execution_audit_append_only();

-- When the application uses its own restricted role (recommended), it only gets SELECT and INSERT.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') AND '${appRole}' <> current_user THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT ON report_execution_audit TO %I', '${appRole}');
    END IF;
END
$$;
