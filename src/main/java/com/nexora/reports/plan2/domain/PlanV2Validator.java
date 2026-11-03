package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.domain.PlanV2Violation.Kind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.nexora.reports.plan2.domain.Trees.map;
import static com.nexora.reports.plan2.domain.Trees.maps;
import static com.nexora.reports.plan2.domain.Trees.string;

/**
 * Independent validation in Reports of the documents of a report plan (A4 proposal v3, section 2), before any
 * translation or connection. Reports does not trust Semantic: it checks again, in this order and failing closed,
 * <ol>
 *   <li>the report plan ({@code nexora-report-plan/1}): schema, RPLAN-R1, profile {@code basic-a}, tenant and data
 *       source of the request, budget against the ceilings of Reports, fingerprint;</li>
 *   <li>the specification ({@code nexora-report-spec/1}): schema, SPEC-R1…SPEC-R6, profile and status, fingerprint
 *       equal to the report plan's {@code specFingerprint}, same datasets as the report plan;</li>
 *   <li>each logical plan ({@code nexora-logical-plan/2}): exactly the plans of the report plan, schema,
 *       PLAN-R1…PLAN-R5, profile {@code basic-a}, identities, recomputed fingerprint equal to the declared one and to
 *       the report plan's {@code planFingerprint}, limits and a single metadata snapshot.</li>
 * </ol>
 * The first failing stage throws a {@link PlanV2Exception} with every violation of that stage.
 */
public final class PlanV2Validator {

    /** The contracts this validator knows, for contract-only verdicts. */
    public enum Contract { REPORT_SPEC, LOGICAL_PLAN, REPORT_PLAN }

    private static final String SCHEMA = "SCHEMA";
    private static final String BASIC_A = "BASIC-A";
    private static final String IDENTITY = "IDENTITY";
    private static final String FINGERPRINT = "FINGERPRINT";
    private static final String BUDGET = "BUDGET";

    private final ContractSchemas schemas;
    private final ReportsCeilings ceilings;
    private final Sha256 sha256;

    public PlanV2Validator(ContractSchemas schemas, ReportsCeilings ceilings, Sha256 sha256) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.ceilings = Objects.requireNonNull(ceilings, "ceilings");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    /**
     * Verdict of a document for its contract only, with the semantics of {@code validar_contratos.py}: {@code OK},
     * {@code SCHEMA}, or the first referential rule that fails.
     */
    public String contractVerdict(Contract contract, Map<String, Object> document) {
        ContractSchema schema = switch (contract) {
            case REPORT_SPEC -> schemas.reportSpec();
            case LOGICAL_PLAN -> schemas.logicalPlan();
            case REPORT_PLAN -> schemas.reportPlan();
        };
        if (schema.firstError(document).isPresent()) {
            return SCHEMA;
        }
        List<String> rules = switch (contract) {
            case REPORT_SPEC -> ContractRules.reportSpec(document);
            case LOGICAL_PLAN -> ContractRules.logicalPlan(document);
            case REPORT_PLAN -> ContractRules.reportPlan(document);
        };
        return rules.isEmpty() ? "OK" : rules.getFirst();
    }

    /** Contract and {@code basic-a} violations of one logical plan, without the cross-document checks. */
    public List<PlanV2Violation> checkLogicalPlan(Map<String, Object> plan, String at) {
        List<PlanV2Violation> violations = new ArrayList<>();
        if (!contract(schemas.logicalPlan(), plan, at, ContractRules::logicalPlan, violations)) {
            return violations;
        }
        BasicAProfile.logicalPlan(plan, at).forEach(p -> violations.add(v(BASIC_A, Kind.PLAN_NOT_SUPPORTED, p)));
        return violations;
    }

    /**
     * Validates the documents of a report plan for a request of {@code tenantId} and {@code dataSourceId} (both taken
     * from the verified identity and the path, never from the documents).
     *
     * @param logicalPlans the logical plans obtained for this report plan, by {@code planId}
     */
    public ValidatedReportPlan validate(UUID tenantId, UUID dataSourceId, Map<String, Object> reportPlan,
                                        Map<String, Object> spec, Map<UUID, Map<String, Object>> logicalPlans) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        checkReportPlan(tenantId, dataSourceId, reportPlan);
        checkSpec(reportPlan, spec);
        checkLogicalPlans(reportPlan, logicalPlans);
        return build(reportPlan, spec, logicalPlans);
    }

    // ---------------------------------------------------------------- stages

    private void checkReportPlan(UUID tenantId, UUID dataSourceId, Map<String, Object> rp) {
        List<PlanV2Violation> violations = new ArrayList<>();
        if (contract(schemas.reportPlan(), rp, "report-plan", ContractRules::reportPlan, violations)) {
            BasicAProfile.reportPlan(rp).forEach(p -> violations.add(v(BASIC_A, Kind.PLAN_NOT_SUPPORTED, p)));
            if (!tenantId.toString().equals(rp.get("tenantId"))) {
                violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, "report-plan.tenantId"));
            }
            if (!dataSourceId.toString().equals(rp.get("dataSourceId"))) {
                violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, "report-plan.dataSourceId"));
            }
            Map<String, Object> budget = map(rp, "budget");
            ceiling(budget, "maxPlans", ceilings.maxPlans(), violations);
            ceiling(budget, "maxRowsPerPlan", ceilings.maxRowsPerPlan(), violations);
            ceiling(budget, "maxTotalRows", ceilings.maxTotalRows(), violations);
            ceiling(budget, "statementTimeoutMs", ceilings.maxStatementTimeoutMs(), violations);
            ceiling(budget, "maxDurationMs", ceilings.maxDurationMs(), violations);
            if (!CanonicalJson.fingerprintOf(rp, sha256).equals(rp.get(CanonicalJson.FINGERPRINT_FIELD))) {
                violations.add(v(FINGERPRINT, Kind.FINGERPRINT_MISMATCH, "report-plan.fingerprint"));
            }
        }
        throwIfAny(violations);
    }

    private void checkSpec(Map<String, Object> rp, Map<String, Object> spec) {
        List<PlanV2Violation> violations = new ArrayList<>();
        if (contract(schemas.reportSpec(), spec, "spec", ContractRules::reportSpec, violations)) {
            BasicAProfile.spec(spec).forEach(p -> violations.add(v(BASIC_A, Kind.PLAN_NOT_SUPPORTED, p)));
            if (!CanonicalJson.sha256(spec, sha256).equals(rp.get("specFingerprint"))) {
                violations.add(v(FINGERPRINT, Kind.FINGERPRINT_MISMATCH, "spec"));
            }
            List<String> specDatasets = maps(spec, "datasets").stream().map(d -> string(d, "id")).toList();
            List<String> planDatasets = maps(rp, "plans").stream().map(p -> string(p, "datasetId")).toList();
            if (specDatasets.size() != planDatasets.size()
                    || !new HashSet<>(specDatasets).equals(new HashSet<>(planDatasets))) {
                violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, "spec.datasets"));
            }
        }
        throwIfAny(violations);
    }

    private void checkLogicalPlans(Map<String, Object> rp, Map<UUID, Map<String, Object>> logicalPlans) {
        List<PlanV2Violation> violations = new ArrayList<>();
        List<Map<String, Object>> refs = maps(rp, "plans");
        Set<UUID> expected = new HashSet<>();
        refs.forEach(ref -> expected.add(UUID.fromString(string(ref, "planId"))));
        if (!expected.equals(logicalPlans.keySet())) {
            violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, "logical-plans"));
            throwIfAny(violations);
        }
        Map<String, Object> budget = map(rp, "budget");
        Set<Object> snapshots = new HashSet<>();
        for (Map<String, Object> ref : refs) {
            String datasetId = string(ref, "datasetId");
            String at = "logical-plan[" + datasetId + "]";
            Map<String, Object> plan = logicalPlans.get(UUID.fromString(string(ref, "planId")));
            List<PlanV2Violation> own = checkLogicalPlan(plan, at);
            violations.addAll(own);
            if (own.stream().anyMatch(x -> x.kind() == Kind.CONTRACT_INVALID)) {
                continue;
            }
            if (!Objects.equals(rp.get("tenantId"), plan.get("tenantId"))) {
                violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, at + ".tenantId"));
            }
            if (!Objects.equals(rp.get("dataSourceId"), plan.get("dataSourceId"))) {
                violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, at + ".dataSourceId"));
            }
            if (!datasetId.equals(plan.get("datasetId"))) {
                violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, at + ".datasetId"));
            }
            if (!Objects.equals(rp.get("specFingerprint"), plan.get("specFingerprint"))) {
                violations.add(v(FINGERPRINT, Kind.FINGERPRINT_MISMATCH, at + ".specFingerprint"));
            }
            String declared = string(plan, CanonicalJson.FINGERPRINT_FIELD);
            if (!CanonicalJson.fingerprintOf(plan, sha256).equals(declared)) {
                violations.add(v(FINGERPRINT, Kind.FINGERPRINT_MISMATCH, at + ".fingerprint"));
            } else if (!declared.equals(ref.get("planFingerprint"))) {
                violations.add(v(FINGERPRINT, Kind.FINGERPRINT_MISMATCH, "report-plan.plans[" + datasetId
                        + "].planFingerprint"));
            }
            Map<String, Object> limit = maps(plan, "operations").getLast();
            if (Trees.number(limit, "maxRows") > Trees.number(budget, "maxRowsPerPlan")) {
                violations.add(v(BUDGET, Kind.BUDGET_EXCEEDED, at + ".limit"));
            }
            if (maps(map(plan, "output"), "columns").size() > ceilings.maxColumnsPerPlan()) {
                violations.add(v(BUDGET, Kind.BUDGET_EXCEEDED, at + ".output.columns"));
            }
            snapshots.add(plan.get("metadataSnapshotId"));
        }
        if (snapshots.size() > 1) {
            violations.add(v(IDENTITY, Kind.IDENTITY_MISMATCH, "logical-plans.metadataSnapshotId"));
        }
        throwIfAny(violations);
    }

    private ValidatedReportPlan build(Map<String, Object> rp, Map<String, Object> spec,
                                      Map<UUID, Map<String, Object>> logicalPlans) {
        ReportPlanV1 reportPlan = PlanV2Parser.reportPlan(rp);
        ReportSpecView specView = PlanV2Parser.spec(spec, CanonicalJson.sha256(spec, sha256));
        Map<String, ValidatedReportPlan.Dataset> datasets = new LinkedHashMap<>();
        for (ReportPlanV1.PlanRef ref : reportPlan.plans()) {
            LogicalPlanV2 plan = PlanV2Parser.logicalPlan(logicalPlans.get(ref.planId()));
            int planLimit = plan.operations().stream().filter(LogicalPlanV2.Limit.class::isInstance)
                    .map(LogicalPlanV2.Limit.class::cast).findFirst().orElseThrow().maxRows();
            int maxRows = Math.min(planLimit, Math.min(reportPlan.budget().maxRowsPerPlan(),
                    ceilings.maxRowsPerPlan()));
            datasets.put(ref.datasetId(), new ValidatedReportPlan.Dataset(ref.datasetId(), ref.planId(), plan,
                    maxRows));
        }
        return new ValidatedReportPlan(reportPlan, specView, List.copyOf(datasets.values()));
    }

    // ---------------------------------------------------------------- helpers

    /** Schema, then referential rules. Returns true if the document is valid for its contract. */
    private static boolean contract(ContractSchema schema, Map<String, Object> document, String at,
                                    java.util.function.Function<Map<String, Object>, List<String>> rules,
                                    List<PlanV2Violation> violations) {
        if (document == null) {
            violations.add(v(SCHEMA, Kind.CONTRACT_INVALID, at));
            return false;
        }
        var error = schema.firstError(document);
        if (error.isPresent()) {
            violations.add(v(SCHEMA, Kind.CONTRACT_INVALID, at + error.get().substring(1)));
            return false;
        }
        List<String> failed = rules.apply(document);
        failed.forEach(rule -> violations.add(v(rule, Kind.CONTRACT_INVALID, at)));
        return failed.isEmpty();
    }

    private static void ceiling(Map<String, Object> budget, String field, long ceiling,
                                List<PlanV2Violation> violations) {
        if (Trees.number(budget, field) > ceiling) {
            violations.add(v(BUDGET, Kind.BUDGET_EXCEEDED, "report-plan.budget." + field));
        }
    }

    private static void throwIfAny(List<PlanV2Violation> violations) {
        if (!violations.isEmpty()) {
            throw new PlanV2Exception(violations);
        }
    }

    private static PlanV2Violation v(String rule, Kind kind, String path) {
        return new PlanV2Violation(rule, kind, path);
    }
}
