package com.nexora.reports.run.infrastructure;

/**
 * Every SQL statement of the run registry (A4-B8), in one place so that a test can check them (D-B8-7): each statement
 * that reads, deletes or relates rows of a run is filtered by {@code tenant_id = ?}, except the two housekeeping
 * statements listed in {@link #CROSS_TENANT}, which act on expired or overdue rows of every tenant and return no
 * content. Statements are parameterized; nothing is concatenated from input.
 */
final class RunSql {

    private RunSql() {
    }

    // ------------------------------------------------------------------ writing

    static final String INSERT_RUN = """
            INSERT INTO report_run (id, tenant_id, data_source_id, report_plan_id, owner_subject, owner_type,
                                    started_at, deadline_at)
            VALUES (?, ?, ?, ?, ?, ?, now(), now() + make_interval(secs => ?::double precision))""";

    static final String INSERT_OUTCOME = """
            INSERT INTO report_run_outcome (run_id, tenant_id, data_source_id, kind, report_plan_fingerprint,
                                            spec_fingerprint, refusal_code, failure_code, snapshot, datasets,
                                            warnings)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""";

    static final String INSERT_ARTIFACT = """
            INSERT INTO report_artifact (id, run_id, tenant_id, data_source_id, media_type, excel_contract, sha256,
                                         size_bytes, sheets, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() + make_interval(secs => ?::double precision))
            RETURNING created_at, expires_at""";

    static final String INSERT_CONTENT = """
            INSERT INTO report_artifact_content (artifact_id, tenant_id, content) VALUES (?, ?, ?)""";

    static final String INSERT_EVENT = """
            INSERT INTO report_run_event (tenant_id, run_id, artifact_id, kind, detail, sha256, size_bytes)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    /** One writer of artifacts per tenant at a time, so that the quota cannot be exceeded by concurrent runs. */
    static final String LOCK_TENANT = "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 4808))";

    static final String QUOTA_USED = """
            SELECT coalesce(sum(a.size_bytes), 0)
              FROM report_artifact a
              JOIN report_artifact_content c ON c.artifact_id = a.id AND c.tenant_id = a.tenant_id
             WHERE a.tenant_id = ? AND a.expires_at > clock_timestamp()""";

    static final String OUTCOME_OF = """
            SELECT o.kind, o.refusal_code, o.failure_code FROM report_run_outcome o
             WHERE o.tenant_id = ? AND o.run_id = ?""";

    // ------------------------------------------------------------------ reading

    static final String FIND_RUN = """
            SELECT r.id, r.tenant_id, r.data_source_id, r.report_plan_id, r.owner_subject, r.owner_type, r.started_at,
                   r.deadline_at, r.deadline_at <= clock_timestamp(),
                   o.kind, o.report_plan_fingerprint, o.spec_fingerprint, o.refusal_code, o.failure_code, o.snapshot,
                   o.finished_at
              FROM report_run r
              LEFT JOIN report_run_outcome o ON o.run_id = r.id AND o.tenant_id = r.tenant_id
             WHERE r.tenant_id = ? AND r.data_source_id = ? AND r.owner_subject = ? AND r.id = ?""";

    static final String FIND_DATASETS = """
            SELECT x."datasetId", x."planId", x."planFingerprint", x."rowCount", x.truncated, x."maxRows",
                   x."durationMs"
              FROM report_run_outcome o
              CROSS JOIN LATERAL ROWS FROM (jsonb_to_recordset(o.datasets) AS ("datasetId" text, "planId" uuid,
                   "planFingerprint" text, "rowCount" integer, truncated boolean, "maxRows" integer,
                   "durationMs" bigint)) WITH ORDINALITY AS x("datasetId", "planId", "planFingerprint", "rowCount",
                   truncated, "maxRows", "durationMs", n)
             WHERE o.tenant_id = ? AND o.run_id = ?
             ORDER BY x.n""";

    static final String FIND_WARNINGS = """
            SELECT x.code, x."datasetId", x.output, x.delivered, x."limit"
              FROM report_run_outcome o
              CROSS JOIN LATERAL ROWS FROM (jsonb_to_recordset(o.warnings) AS (code text, "datasetId" text,
                   output text, delivered bigint, "limit" bigint)) WITH ORDINALITY AS x(code, "datasetId", output,
                   delivered, "limit", n)
             WHERE o.tenant_id = ? AND o.run_id = ?
             ORDER BY x.n""";

    static final String FIND_ARTIFACT = """
            SELECT a.id, a.run_id, a.tenant_id, a.data_source_id, a.media_type, a.excel_contract, a.sha256,
                   a.size_bytes, a.sheets, a.created_at, a.expires_at, a.expires_at <= clock_timestamp(),
                   EXISTS (SELECT 1 FROM report_artifact_content c
                            WHERE c.artifact_id = a.id AND c.tenant_id = a.tenant_id)
              FROM report_artifact a
              JOIN report_run r ON r.id = a.run_id AND r.tenant_id = a.tenant_id
             WHERE a.tenant_id = ? AND a.data_source_id = ? AND r.owner_subject = ? AND a.run_id = ?""";

    static final String READ_CONTENT = """
            SELECT c.content FROM report_artifact_content c WHERE c.tenant_id = ? AND c.artifact_id = ?""";

    // ------------------------------------------------------------------ housekeeping (every tenant)

    static final String PURGE_EXPIRED = """
            DELETE FROM report_artifact_content c
             USING report_artifact a
             WHERE a.id = c.artifact_id AND a.tenant_id = c.tenant_id
               AND a.expires_at <= clock_timestamp()
               AND c.artifact_id IN (
                   SELECT a2.id FROM report_artifact a2
                     JOIN report_artifact_content c2 ON c2.artifact_id = a2.id AND c2.tenant_id = a2.tenant_id
                    WHERE a2.expires_at <= clock_timestamp()
                    ORDER BY a2.expires_at, a2.id LIMIT ?)
            RETURNING a.id, a.run_id, a.tenant_id, a.sha256, a.size_bytes""";

    static final String CLOSE_ABANDONED = """
            INSERT INTO report_run_outcome (run_id, tenant_id, data_source_id, kind, datasets, warnings)
            SELECT r.id, r.tenant_id, r.data_source_id, 'INTERRUPTED', '[]'::jsonb, '[]'::jsonb
              FROM report_run r
             WHERE r.deadline_at <= clock_timestamp()
               AND NOT EXISTS (SELECT 1 FROM report_run_outcome o WHERE o.run_id = r.id)
             ORDER BY r.deadline_at, r.id LIMIT ?
            ON CONFLICT (run_id) DO NOTHING
            RETURNING run_id, tenant_id""";

    /** The only statements that act across tenants: housekeeping of expired content and overdue runs. */
    static final java.util.Set<String> CROSS_TENANT = java.util.Set.of("PURGE_EXPIRED", "CLOSE_ABANDONED");
}
