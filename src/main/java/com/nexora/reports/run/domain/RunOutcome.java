package com.nexora.reports.run.domain;

import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ReportResult;
import com.nexora.reports.result.domain.ResultStatus;
import com.nexora.reports.result.domain.ResultWarning;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How a run ended (A4-B8, adjustment 1): RUN-R1 extended to every real state, checked here and again by the database.
 * <ul>
 *   <li>COMPLETED: no refusal, no failure, fingerprints and snapshot present, no truncated dataset; artifact required;</li>
 *   <li>PARTIAL: as COMPLETED, with at least one truncated dataset;</li>
 *   <li>REFUSED: a refusal code, no failure, never an artifact;</li>
 *   <li>FAILED: a {@link FailureCode}, no refusal, never an artifact;</li>
 *   <li>INTERRUPTED: neither, never an artifact; only the reconciliation records it.</li>
 * </ul>
 * A refusal or failure that happens after the data was read keeps the fingerprints, snapshot, datasets and warnings
 * already known ({@link #withRefusal}, {@link #withFailure}). Nothing here holds business data: ids, codes, counts.
 */
public record RunOutcome(RunOutcomeKind kind, String reportPlanFingerprint, String specFingerprint,
                         String refusalCode, FailureCode failureCode, String snapshot,
                         List<Methodology.DatasetExecution> datasets, List<ResultWarning> warnings) {

    /** Refusal of {@code nexora-report-run/1} when the tenant's quota of current artifacts would be exceeded. */
    public static final String ARTIFACT_QUOTA_EXCEEDED = "artifact_quota_exceeded";
    /** Refusal when the file is over the size limit. */
    public static final String ARTIFACT_TOO_LARGE = "artifact_too_large";

    public static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    public static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    public static final Pattern SNAPSHOT = Pattern.compile("\\d+:\\d+:(\\d+(,\\d+)*)?");

    public RunOutcome {
        Objects.requireNonNull(kind, "kind");
        datasets = List.copyOf(datasets);
        warnings = List.copyOf(warnings);
        check(reportPlanFingerprint == null || FINGERPRINT.matcher(reportPlanFingerprint).matches(), "fingerprint");
        check(specFingerprint == null || FINGERPRINT.matcher(specFingerprint).matches(), "spec fingerprint");
        check(refusalCode == null || CODE.matcher(refusalCode).matches(), "refusal code");
        check(snapshot == null || SNAPSHOT.matcher(snapshot).matches(), "snapshot");
        boolean truncated = datasets.stream().anyMatch(Methodology.DatasetExecution::truncated);
        switch (kind) {
            case COMPLETED, PARTIAL -> {
                check(refusalCode == null && failureCode == null, "a delivered run has no refusal or failure");
                check(reportPlanFingerprint != null && specFingerprint != null && snapshot != null,
                        "a delivered run has its fingerprints and snapshot");
                check(truncated == (kind == RunOutcomeKind.PARTIAL), "PARTIAL if and only if a dataset is truncated");
            }
            case REFUSED -> check(refusalCode != null && failureCode == null, "REFUSED has a refusal code only");
            case FAILED -> check(failureCode != null && refusalCode == null, "FAILED has a failure code only");
            case INTERRUPTED -> check(refusalCode == null && failureCode == null, "INTERRUPTED has no code");
        }
    }

    private static void check(boolean condition, String rule) {
        if (!condition) {
            throw new IllegalArgumentException("invalid run outcome: " + rule);
        }
    }

    /** COMPLETED or PARTIAL, as the assembled result says. */
    public static RunOutcome of(ReportResult result) {
        return new RunOutcome(result.status() == ResultStatus.PARTIAL ? RunOutcomeKind.PARTIAL
                : RunOutcomeKind.COMPLETED, result.run().reportPlanFingerprint(), result.run().specFingerprint(),
                null, null, result.run().snapshot(), result.methodology().datasets(), result.warnings());
    }

    /** REFUSED before anything was read; the fingerprints may be unknown. */
    public static RunOutcome refused(String code, String reportPlanFingerprint, String specFingerprint) {
        return new RunOutcome(RunOutcomeKind.REFUSED, reportPlanFingerprint, specFingerprint, code, null, null,
                List.of(), List.of());
    }

    /** FAILED before anything was read. */
    public static RunOutcome failed(FailureCode code) {
        return new RunOutcome(RunOutcomeKind.FAILED, null, null, null, Objects.requireNonNull(code, "code"), null,
                List.of(), List.of());
    }

    public static RunOutcome interrupted() {
        return new RunOutcome(RunOutcomeKind.INTERRUPTED, null, null, null, null, null, List.of(), List.of());
    }

    /** This run refused after the fact (for example over the quota), keeping what is already known. */
    public RunOutcome withRefusal(String code) {
        return new RunOutcome(RunOutcomeKind.REFUSED, reportPlanFingerprint, specFingerprint, code, null, snapshot,
                datasets, warnings);
    }

    /** This run failed after the fact (for example while storing), keeping what is already known. */
    public RunOutcome withFailure(FailureCode code) {
        return new RunOutcome(RunOutcomeKind.FAILED, reportPlanFingerprint, specFingerprint, null,
                Objects.requireNonNull(code, "code"), snapshot, datasets, warnings);
    }
}
