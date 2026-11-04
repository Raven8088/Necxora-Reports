package com.nexora.reports.execution.application;

import com.nexora.reports.plan2.domain.ContractSchema;
import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ResultWarning;
import com.nexora.reports.run.domain.RunOutcome;
import com.nexora.reports.run.domain.RunOutcomeKind;
import com.nexora.reports.run.domain.RunView;
import com.nexora.reports.run.domain.StoredArtifact;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A4-B9 (D-B9-3): the {@code nexora-report-run/1} and {@code nexora-report-artifact/1} documents of a recorded run,
 * built only from what the run registry holds (A4-B8), so the answer of the run and every later read of it are the
 * same document. Every document is validated against the real contract schemas and RUN-R1 before it leaves Reports;
 * one that does not validate is never returned (fails closed).
 * <p>
 * Warning and refusal messages are generated here from their stable codes; they never contain business data.
 */
public final class ReportRunDocuments {

    public static final String RUN_FORMAT = "nexora-report-run/1";
    public static final String ARTIFACT_FORMAT = "nexora-report-artifact/1";
    public static final String OK = "OK";
    public static final String SCHEMA = "SCHEMA";
    public static final String RUN_R1 = "RUN-R1";

    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ISO_INSTANT;

    private static final Map<String, String> REFUSALS = Map.ofEntries(
            Map.entry("plan_stale", "El plan de reporte ya no está vigente en Semantic; hay que compilarlo de nuevo."),
            Map.entry("plan_changed_during_execution",
                    "El plan de reporte dejó de estar vigente durante la ejecución; el resultado se descartó."),
            Map.entry("plan_validity_unconfirmed", "Semantic no pudo confirmar que el plan de reporte esté vigente."),
            Map.entry("tenant_scope_unverified",
                    "No se pudo verificar la tenencia de la fuente de datos; no se leyó ningún dato."),
            Map.entry("physical_schema_mismatch", "El esquema actual de la base de datos no coincide con el plan."),
            Map.entry("result_too_large", "El resultado supera el límite de filas o de memoria de una ejecución."),
            Map.entry("value_not_representable",
                    "Un valor de la fuente no se puede representar exactamente en su tipo de salida."),
            Map.entry("too_many_executions", "Hay demasiadas ejecuciones simultáneas; vuelva a intentarlo más tarde."),
            Map.entry("execution_timeout", "La ejecución superó su tiempo máximo."),
            Map.entry("execution_failed", "La lectura de la fuente de datos falló."),
            Map.entry("data_source_unavailable", "La fuente de datos no está disponible."),
            Map.entry("data_source_role_not_read_only",
                    "El rol configurado para la fuente de datos no es estrictamente de solo lectura."),
            Map.entry("data_source_target_not_allowed", "El destino configurado de la fuente de datos no está permitido."),
            Map.entry("contract_invalid", "Un documento del plan de reporte no cumple su contrato."),
            Map.entry("plan_not_supported", "El plan de reporte está fuera de lo que esta versión de Reports ejecuta."),
            Map.entry("identity_mismatch",
                    "Los documentos del plan de reporte no corresponden al tenant, la fuente o entre sí."),
            Map.entry("fingerprint_mismatch", "Una huella declarada no coincide con la recalculada."),
            Map.entry("budget_exceeded", "El plan de reporte supera un límite permitido."),
            Map.entry("spec_mismatch", "La especificación no es la que se validó con el plan de reporte."),
            Map.entry("report_layout_invalid", "Las secciones del reporte no corresponden a sus datasets."),
            Map.entry(RunOutcome.ARTIFACT_QUOTA_EXCEEDED, "Se superó la cuota de archivos vigentes del tenant."),
            Map.entry(RunOutcome.ARTIFACT_TOO_LARGE, "El archivo superaría el tamaño máximo permitido."));

    private final ContractSchema runSchema;
    private final ContractSchema artifactSchema;

    public ReportRunDocuments(ContractSchema runSchema, ContractSchema artifactSchema) {
        this.runSchema = Objects.requireNonNull(runSchema, "runSchema");
        this.artifactSchema = Objects.requireNonNull(artifactSchema, "artifactSchema");
    }

    /**
     * The {@code nexora-report-run/1} of a COMPLETED, PARTIAL or REFUSED run, or empty when the run cannot be described
     * by that contract: no outcome yet, FAILED or INTERRUPTED (internal states), or a refusal whose report plan
     * fingerprint is unknown (the contract requires it).
     *
     * @throws IllegalStateException if the built document does not validate (never returned)
     */
    public Optional<Map<String, Object>> reportRun(RunView view) {
        Objects.requireNonNull(view, "view");
        if (view.outcome().isEmpty() || view.finishedAt().isEmpty()) {
            return Optional.empty();
        }
        RunOutcome outcome = view.outcome().get();
        RunOutcomeKind kind = outcome.kind();
        if (kind == RunOutcomeKind.FAILED || kind == RunOutcomeKind.INTERRUPTED
                || outcome.reportPlanFingerprint() == null) {
            return Optional.empty();
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", RUN_FORMAT);
        doc.put("runId", view.runId().toString());
        doc.put("reportPlanFingerprint", outcome.reportPlanFingerprint());
        doc.put("status", kind.name());
        doc.put("startedAt", instant(view.startedAt()));
        doc.put("finishedAt", instant(view.finishedAt().get()));
        Map<String, Object> consistency = new LinkedHashMap<>();
        consistency.put("isolation", "REPEATABLE_READ");
        consistency.put("readOnly", true);
        consistency.put("transactionCount", 1L);
        if (outcome.snapshot() != null) {
            consistency.put("snapshot", outcome.snapshot());
        }
        doc.put("consistency", consistency);
        List<Object> datasets = new ArrayList<>();
        for (Methodology.DatasetExecution d : outcome.datasets()) {
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("datasetId", d.datasetId());
            dataset.put("planId", d.planId().toString());
            dataset.put("rowCount", (long) d.rowCount());
            dataset.put("truncated", d.truncated());
            dataset.put("maxRows", (long) d.maxRows());
            dataset.put("durationMs", d.durationMs());
            datasets.add(dataset);
        }
        doc.put("datasets", datasets);
        List<Object> warnings = new ArrayList<>();
        for (ResultWarning w : outcome.warnings()) {
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("code", w.code());
            warning.put("message", warningMessage(w));
            if (w.datasetId() != null) {
                warning.put("datasetId", w.datasetId());
            }
            warnings.add(warning);
        }
        doc.put("warnings", warnings);
        if (kind == RunOutcomeKind.REFUSED) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("code", outcome.refusalCode());
            refusal.put("message", refusalMessage(outcome.refusalCode()));
            doc.put("refusal", refusal);
            doc.put("artifact", null);
        } else {
            doc.put("refusal", null);
            doc.put("artifact", artifact(view.artifact().orElseThrow(
                    () -> new IllegalStateException("a delivered run without its artifact"))));
        }
        String verdict = runVerdict(doc);
        if (!OK.equals(verdict)) {
            throw new IllegalStateException("built nexora-report-run/1 is not valid: " + verdict);
        }
        return Optional.of(doc);
    }

    /** The {@code nexora-report-artifact/1} of a stored artifact, validated against its schema. */
    public Map<String, Object> artifact(StoredArtifact a) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", ARTIFACT_FORMAT);
        doc.put("artifactId", a.artifactId().toString());
        doc.put("runId", a.runId().toString());
        doc.put("mediaType", a.mediaType());
        doc.put("excelContract", a.excelContract());
        doc.put("sha256", a.sha256());
        doc.put("sizeBytes", a.sizeBytes());
        doc.put("sheets", new ArrayList<Object>(a.sheets()));
        doc.put("createdAt", instant(a.createdAt()));
        doc.put("expiresAt", instant(a.expiresAt()));
        if (artifactSchema.firstError(doc).isPresent()) {
            throw new IllegalStateException("built nexora-report-artifact/1 is not valid");
        }
        return doc;
    }

    /**
     * Verdict of a {@code nexora-report-run/1} with the semantics of the contract's reference validator: {@code OK},
     * {@code SCHEMA} or {@code RUN-R1}.
     */
    public String runVerdict(Map<String, Object> document) {
        if (runSchema.firstError(document).isPresent()) {
            return SCHEMA;
        }
        return runR1(document) ? OK : RUN_R1;
    }

    /** Verdict of a {@code nexora-report-artifact/1}: {@code OK} or {@code SCHEMA}. */
    public String artifactVerdict(Map<String, Object> document) {
        return artifactSchema.firstError(document).isPresent() ? SCHEMA : OK;
    }

    /**
     * RUN-R1: COMPLETED has no truncated dataset, no refusal and an artifact; PARTIAL has at least one truncated
     * dataset, no refusal and an artifact; REFUSED has a refusal and no artifact.
     */
    static boolean runR1(Map<String, Object> document) {
        Object status = document.get("status");
        boolean truncated = false;
        if (document.get("datasets") instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("truncated"))) {
                    truncated = true;
                }
            }
        }
        boolean refused = document.get("refusal") != null;
        boolean artifact = document.get("artifact") != null;
        return switch (String.valueOf(status)) {
            case "COMPLETED" -> !truncated && !refused && artifact;
            case "PARTIAL" -> truncated && !refused && artifact;
            case "REFUSED" -> refused && !artifact;
            default -> false;
        };
    }

    static String refusalMessage(String code) {
        String message = REFUSALS.get(code);
        return message != null ? message : "La ejecución fue rechazada (código " + code + ").";
    }

    static String warningMessage(ResultWarning w) {
        return switch (w.code()) {
            case ResultWarning.DATASET_TRUNCATED -> "El dataset " + w.datasetId() + " tiene más filas que su límite de "
                    + w.limit() + "; se entregan las primeras " + w.delivered() + " en el orden indicado.";
            case ResultWarning.CELL_TEXT_TRUNCATED -> "Se recortaron " + w.delivered() + " textos del dataset "
                    + w.datasetId() + " que superaban " + w.limit() + " caracteres.";
            case ResultWarning.TRANSPORT_NOT_VERIFIED -> "La ejecución se hizo con una excepción temporal de"
                    + " transporte (EX-TLS-1): la conexión con la fuente no estaba cifrada y la identidad del"
                    + " servidor no se verificó.";
            default -> "Aviso " + w.code() + " en el dataset " + w.datasetId() + ".";
        };
    }

    private static String instant(Instant instant) {
        return INSTANT.format(instant);
    }
}
