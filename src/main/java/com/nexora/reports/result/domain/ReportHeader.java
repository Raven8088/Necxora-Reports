package com.nexora.reports.result.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the top of a report states (EX-02): title and request of the specification, source and tenant, generation
 * instant and the zone it is shown in, filters, Reports version. The tenant's name is unknown to Reports (I-6).
 */
public record ReportHeader(String title, String requestText, String requestLanguage, ZoneId timeZone,
                           String labelLanguage, UUID tenantId, Fact tenantName, UUID dataSourceId, Fact sourceName,
                           Instant generatedAt, String reportsVersion, List<FilterDescription> filters) {

    public ReportHeader {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tenantName, "tenantName");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(generatedAt, "generatedAt");
        filters = List.copyOf(filters);
    }
}
