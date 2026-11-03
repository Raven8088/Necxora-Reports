package com.nexora.reports.execution.domain;

import com.nexora.reports.credentials.domain.DataSourceCredentials;
import com.nexora.reports.plan2.domain.ValidatedReportPlan;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation2.domain.SqlQueryV2;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What the {@code /2} executor runs: the translated queries of one validated report plan, in its dataset order, for
 * exactly one (tenant, data source). Its invariants are checked before any connection is opened.
 */
public record ExecutionRequestV2(UUID tenantId, UUID dataSourceId, List<SqlQueryV2> queries) {

    public ExecutionRequestV2 {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        queries = List.copyOf(queries);
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("no queries");
        }
        Set<String> ids = new HashSet<>();
        for (SqlQueryV2 query : queries) {
            if (!ids.add(query.datasetId())) {
                throw new IllegalArgumentException("duplicate dataset");
            }
            List<SqlParameter> parameters = query.parameters();
            SqlParameter limit = parameters.isEmpty() ? null : parameters.getLast();
            if (limit == null || limit.type() != SqlParameter.SqlType.BIGINT
                    || !Long.valueOf(query.maxRows() + 1L).equals(limit.value())) {
                throw new IllegalArgumentException("the last parameter is not the row limit + 1");
            }
            if (query.outputs().isEmpty()) {
                throw new IllegalArgumentException("query without outputs");
            }
            for (int i = 0; i < query.outputs().size(); i++) {
                SqlQueryV2.Output output = query.outputs().get(i);
                if (output.position() != i + 1) {
                    throw new IllegalArgumentException("output positions are not consecutive");
                }
                OutputValueType.of(output.valueType());
            }
        }
    }

    /** The queries must be the translation of the plan's datasets, in the same order and with the same limits. */
    public static ExecutionRequestV2 of(ValidatedReportPlan plan, List<SqlQueryV2> queries) {
        List<ValidatedReportPlan.Dataset> datasets = plan.datasets();
        if (datasets.size() != queries.size()) {
            throw new IllegalArgumentException("one query per dataset is required");
        }
        for (int i = 0; i < datasets.size(); i++) {
            if (!datasets.get(i).datasetId().equals(queries.get(i).datasetId())
                    || datasets.get(i).maxRows() != queries.get(i).maxRows()) {
                throw new IllegalArgumentException("queries do not match the datasets of the plan");
            }
        }
        return new ExecutionRequestV2(plan.reportPlan().tenantId(), plan.reportPlan().dataSourceId(), queries);
    }

    /** Fails before connecting if the credentials belong to another tenant or data source. */
    public void requireSameSource(DataSourceCredentials credentials) {
        if (!tenantId.equals(credentials.tenantId()) || !dataSourceId.equals(credentials.dataSourceId())) {
            throw new IllegalArgumentException("credentials of another tenant or data source");
        }
    }
}
