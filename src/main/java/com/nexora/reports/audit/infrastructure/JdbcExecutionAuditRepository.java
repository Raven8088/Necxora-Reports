package com.nexora.reports.audit.infrastructure;

import com.nexora.reports.audit.domain.ExecutionAuditRecord;
import com.nexora.reports.execution.application.port.AuditPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only audit in Reports' own database (table {@code report_execution_audit}, migration V1). Every row carries
 * its tenant and every read is scoped by tenant. Rows cannot be updated or deleted (trigger).
 */
public class JdbcExecutionAuditRepository implements AuditPort {

    private final JdbcClient jdbc;

    public JdbcExecutionAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(ExecutionAuditRecord r) {
        jdbc.sql("""
                        INSERT INTO report_execution_audit (execution_id, tenant_id, data_source_id, plan_id,
                               requester_subject, requester_type, plan_fingerprint, validity_status, outcome,
                               error_code, row_count, truncated, duration_ms, started_at)
                        VALUES (:executionId, :tenantId, :dataSourceId, :planId, :subject, :type, :fingerprint,
                                :validity, :outcome, :errorCode, :rowCount, :truncated, :durationMs, :startedAt)""")
                .param("executionId", r.executionId())
                .param("tenantId", r.tenantId())
                .param("dataSourceId", r.dataSourceId())
                .param("planId", r.planId())
                .param("subject", r.requesterSubject())
                .param("type", r.requesterType())
                .param("fingerprint", r.planFingerprint())
                .param("validity", r.validityStatus())
                .param("outcome", r.outcome().name())
                .param("errorCode", r.errorCode())
                .param("rowCount", r.rowCount())
                .param("truncated", r.truncated())
                .param("durationMs", r.durationMillis())
                .param("startedAt", Timestamp.from(r.startedAt()))
                .update();
    }

    /** Most recent records of one tenant (operations and tests; not exposed by the R1 API). */
    public List<ExecutionAuditRecord> findByTenant(UUID tenantId, int limit) {
        return jdbc.sql("""
                        SELECT execution_id, tenant_id, data_source_id, plan_id, requester_subject, requester_type,
                               plan_fingerprint, validity_status, outcome, error_code, row_count, truncated,
                               duration_ms, started_at
                          FROM report_execution_audit
                         WHERE tenant_id = :tenantId
                         ORDER BY recorded_at DESC, execution_id
                         LIMIT :limit""")
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query((rs, n) -> new ExecutionAuditRecord(rs.getObject("execution_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("data_source_id", UUID.class),
                        rs.getObject("plan_id", UUID.class), rs.getString("requester_subject"),
                        rs.getString("requester_type"), rs.getString("plan_fingerprint"),
                        rs.getString("validity_status"),
                        ExecutionAuditRecord.Outcome.valueOf(rs.getString("outcome")), rs.getString("error_code"),
                        (Integer) rs.getObject("row_count"), rs.getBoolean("truncated"), rs.getLong("duration_ms"),
                        instant(rs.getTimestamp("started_at"))))
                .list();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
