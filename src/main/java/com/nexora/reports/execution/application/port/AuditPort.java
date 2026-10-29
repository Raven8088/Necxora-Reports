package com.nexora.reports.execution.application.port;

import com.nexora.reports.audit.domain.ExecutionAuditRecord;

/** Durable, tenant-scoped technical audit of executions. A failure to record must surface as an exception. */
public interface AuditPort {

    void record(ExecutionAuditRecord record);
}
