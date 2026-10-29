package com.nexora.reports.plan.infrastructure;

import com.nexora.reports.execution.application.port.PlanSource;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.domain.DataSourceDescriptor;
import com.nexora.reports.plan.domain.PlanValidity;
import com.nexora.reports.plan.domain.StoredPlan;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PlanSource} over Semantic's REST API, always with the service token of the requester's tenant:
 * <ul>
 *   <li>{@code GET /api/v1/data-sources/{id}} ({@code semantic:catalog:read})</li>
 *   <li>{@code GET /api/v1/data-sources/{id}/logical-plans/{planId}} ({@code semantic:resolve})</li>
 *   <li>{@code GET /api/v1/data-sources/{id}/logical-plans/{planId}/validity} ({@code semantic:resolve})</li>
 * </ul>
 * Paths are built from UUIDs only. Semantic answers 404 for resources of other tenants, which Reports reports as not
 * found as well.
 */
public class SemanticPlanSource implements PlanSource {

    private final URI baseUrl;
    private final BoundedHttp http;
    private final ServiceTokenProvider tokens;
    private final PlanDocumentReader reader;

    public SemanticPlanSource(URI baseUrl, BoundedHttp http, ServiceTokenProvider tokens, PlanDocumentReader reader) {
        String base = Objects.requireNonNull(baseUrl).toString();
        this.baseUrl = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.http = Objects.requireNonNull(http);
        this.tokens = Objects.requireNonNull(tokens);
        this.reader = Objects.requireNonNull(reader);
    }

    @Override
    public DataSourceDescriptor dataSource(UUID tenantId, UUID dataSourceId) {
        byte[] body = get(tenantId, "/api/v1/data-sources/" + dataSourceId, ErrorCode.DATA_SOURCE_NOT_FOUND);
        return reader.dataSource(body);
    }

    @Override
    public StoredPlan plan(UUID tenantId, UUID dataSourceId, UUID planId) {
        byte[] body = get(tenantId, "/api/v1/data-sources/" + dataSourceId + "/logical-plans/" + planId,
                ErrorCode.PLAN_NOT_FOUND);
        return reader.plan(body);
    }

    @Override
    public PlanValidity validity(UUID tenantId, UUID dataSourceId, UUID planId) {
        byte[] body;
        try {
            body = get(tenantId, "/api/v1/data-sources/" + dataSourceId + "/logical-plans/" + planId + "/validity",
                    ErrorCode.PLAN_NOT_FOUND);
        } catch (ExecutionException e) {
            if (e.code() == ErrorCode.SEMANTIC_UNAVAILABLE || e.code() == ErrorCode.SEMANTIC_CONTRACT_VIOLATION) {
                // Validity that cannot be confirmed is never assumed.
                throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "validity unavailable", e);
            }
            throw e;
        }
        return reader.validity(body);
    }

    private byte[] get(UUID tenantId, String path, ErrorCode notFound) {
        String token = tokens.tokenFor(tenantId);
        BoundedHttp.Response response;
        try {
            response = http.get(URI.create(baseUrl + path), token);
        } catch (BoundedHttp.TransportException e) {
            throw new ExecutionException(ErrorCode.SEMANTIC_UNAVAILABLE, "Semantic unreachable", e);
        }
        int status = response.status();
        if (status == 200) {
            return response.body();
        }
        if (status == 404) {
            throw new ExecutionException(notFound, "Semantic answered 404");
        }
        if (status == 401) {
            tokens.evict(tenantId);
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "Semantic answered 401");
        }
        if (status == 403) {
            if ("tenant_access_denied".equals(reader.problemCode(response.body()))) {
                throw new ExecutionException(ErrorCode.REQUESTER_NOT_ALLOWED, "tenant not active in Semantic");
            }
            throw new ExecutionException(ErrorCode.SERVICE_IDENTITY_INVALID, "Semantic answered 403");
        }
        if (status == 409) {
            throw new ExecutionException(ErrorCode.DATA_SOURCE_NOT_EXECUTABLE, "Semantic answered 409");
        }
        if (status >= 500) {
            throw new ExecutionException(ErrorCode.SEMANTIC_UNAVAILABLE, "Semantic answered " + status);
        }
        throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "Semantic answered " + status);
    }
}
