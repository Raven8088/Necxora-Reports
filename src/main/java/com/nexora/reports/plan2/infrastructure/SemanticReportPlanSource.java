package com.nexora.reports.plan2.infrastructure;

import com.nexora.reports.execution.application.port.ReportPlanSource;
import com.nexora.reports.execution.domain.ExecutionException;
import com.nexora.reports.execution.domain.ExecutionException.ErrorCode;
import com.nexora.reports.plan.infrastructure.BoundedHttp;
import com.nexora.reports.plan.infrastructure.PlanDocumentReader;
import com.nexora.reports.plan.infrastructure.ServiceTokenProvider;
import com.nexora.reports.plan2.domain.PlanV2Exception;
import com.nexora.reports.plan2.domain.ReportPlanValidity;
import com.nexora.reports.plan2.domain.SemanticDataSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A4-B9 (D-B9-2): {@link ReportPlanSource} over Semantic's REST API (A3), always with the service token of the
 * requester's tenant (REPORTS_CLIENT):
 * <ul>
 *   <li>{@code GET /api/v1/data-sources/{ds}}: id, engine, status and name of the source;</li>
 *   <li>{@code GET /api/v1/data-sources/{ds}/report-plans/{rp}}: the {@code nexora-report-plan/1}, as stored;</li>
 *   <li>{@code GET .../report-plans/{rp}/spec}: the compiled {@code nexora-report-spec/1};</li>
 *   <li>{@code GET .../report-plans/{rp}/logical-plans/{planId}}: each {@code nexora-logical-plan/2};</li>
 *   <li>{@code GET .../report-plans/{rp}/validity}: {@code {reportPlanId, status, issues}}.</li>
 * </ul>
 * Paths are built from UUIDs only. Documents are read with the strict reader of B2 (duplicate keys, trailing content
 * and fractional numbers make them unreadable) and validated later by Reports itself. Semantic answers 404 for
 * resources of other tenants, which Reports reports as not found as well. A specification or logical plan that the
 * report plan names but Semantic cannot serve is a contract violation of Semantic, not a missing resource.
 */
public class SemanticReportPlanSource implements ReportPlanSource {

    private static final Set<String> VALIDITY_FIELDS = Set.of("reportPlanId", "status", "issues");

    private final URI baseUrl;
    private final BoundedHttp http;
    private final ServiceTokenProvider tokens;
    private final PlanDocumentReader problems;
    private final StrictJsonTreeReader reader;

    public SemanticReportPlanSource(URI baseUrl, BoundedHttp http, ServiceTokenProvider tokens,
                                    PlanDocumentReader problems, StrictJsonTreeReader reader) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl").toString();
        this.baseUrl = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.problems = Objects.requireNonNull(problems, "problems");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public SemanticDataSource dataSource(UUID tenantId, UUID dataSourceId) {
        Map<String, Object> tree = tree(get(tenantId, source(dataSourceId), ErrorCode.DATA_SOURCE_NOT_FOUND),
                ErrorCode.SEMANTIC_CONTRACT_VIOLATION);
        if (!(tree.get("id") instanceof String id) || !(tree.get("engine") instanceof String engine)
                || !(tree.get("status") instanceof String status)) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "data source document");
        }
        Object name = tree.get("name");
        if (name != null && !(name instanceof String)) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "data source name");
        }
        try {
            return new SemanticDataSource(UUID.fromString(id), engine, status,
                    Optional.ofNullable((String) name).filter(n -> !n.isBlank()));
        } catch (IllegalArgumentException e) {
            throw new ExecutionException(ErrorCode.SEMANTIC_CONTRACT_VIOLATION, "data source id");
        }
    }

    @Override
    public Map<String, Object> reportPlan(UUID tenantId, UUID dataSourceId, UUID reportPlanId) {
        return reader.readObject(get(tenantId, plan(dataSourceId, reportPlanId), ErrorCode.PLAN_NOT_FOUND),
                "report-plan");
    }

    @Override
    public Map<String, Object> spec(UUID tenantId, UUID dataSourceId, UUID reportPlanId) {
        return reader.readObject(get(tenantId, plan(dataSourceId, reportPlanId) + "/spec",
                ErrorCode.SEMANTIC_CONTRACT_VIOLATION), "spec");
    }

    @Override
    public Map<String, Object> logicalPlan(UUID tenantId, UUID dataSourceId, UUID reportPlanId, UUID planId) {
        return reader.readObject(get(tenantId, plan(dataSourceId, reportPlanId) + "/logical-plans/"
                + Objects.requireNonNull(planId, "planId"), ErrorCode.SEMANTIC_CONTRACT_VIOLATION), "logical-plan");
    }

    @Override
    public ReportPlanValidity validity(UUID tenantId, UUID dataSourceId, UUID reportPlanId) {
        byte[] body;
        try {
            body = get(tenantId, plan(dataSourceId, reportPlanId) + "/validity", ErrorCode.PLAN_NOT_FOUND);
        } catch (ExecutionException e) {
            if (e.code() == ErrorCode.SEMANTIC_UNAVAILABLE || e.code() == ErrorCode.SEMANTIC_CONTRACT_VIOLATION) {
                // Validity that cannot be confirmed is never assumed.
                throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "validity unavailable", e);
            }
            throw e;
        }
        Map<String, Object> tree = tree(body, ErrorCode.PLAN_VALIDITY_UNCONFIRMED);
        if (!VALIDITY_FIELDS.equals(tree.keySet()) || !reportPlanId.toString().equals(tree.get("reportPlanId"))
                || !(tree.get("issues") instanceof List<?> issues)) {
            throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "unexpected validity document");
        }
        List<String> codes = new ArrayList<>();
        for (Object issue : issues) {
            if (!(issue instanceof Map<?, ?> map) || !(map.get("code") instanceof String code)) {
                throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "unexpected validity issue");
            }
            codes.add(code);
        }
        ReportPlanValidity.Status status;
        if ("CURRENT".equals(tree.get("status"))) {
            status = ReportPlanValidity.Status.CURRENT;
        } else if ("STALE".equals(tree.get("status"))) {
            status = ReportPlanValidity.Status.STALE;
        } else {
            throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "unknown validity status");
        }
        if (status == ReportPlanValidity.Status.CURRENT && !codes.isEmpty()) {
            throw new ExecutionException(ErrorCode.PLAN_VALIDITY_UNCONFIRMED, "CURRENT with issues");
        }
        return new ReportPlanValidity(status, codes);
    }

    private static String source(UUID dataSourceId) {
        return "/api/v1/data-sources/" + Objects.requireNonNull(dataSourceId, "dataSourceId");
    }

    private static String plan(UUID dataSourceId, UUID reportPlanId) {
        return source(dataSourceId) + "/report-plans/" + Objects.requireNonNull(reportPlanId, "reportPlanId");
    }

    private Map<String, Object> tree(byte[] body, ErrorCode unreadable) {
        try {
            return reader.readObject(body, "semantic-response");
        } catch (PlanV2Exception e) {
            throw new ExecutionException(unreadable, "unreadable Semantic response");
        }
    }

    /** Same answers as R1's client (SemanticPlanSource), for the same reasons. */
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
            if ("tenant_access_denied".equals(problems.problemCode(response.body()))) {
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
