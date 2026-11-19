package com.nexora.reports.result.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the methodology of a report states (EX-14 to EX-16), every datum known or explicitly unavailable with a
 * reason code; nothing is filled in with an assumption (A4 proposal v3, section 4 bis.7).
 *
 * @param relations        tables and views read, in order of first use
 * @param relationships    approved relationships used by joins; who approved them is not in the plan (I-3)
 * @param terms            the request's terms and their resolution ({@code provenance})
 * @param definitions      governed definitions behind output columns
 * @param orderRules       the order of every dataset (direction, nulls, collation)
 * @param datasets         rows, truncation and duration of every dataset
 * @param excludedColumns  columns excluded by the exposure policy: unavailable unless the run provides them (I-4)
 * @param model            the model that interpreted the request: not recorded by the specification (I-5)
 * @param units            units of measures: not carried by the contracts
 */
public record Methodology(List<SourceObject> relations, List<RelationshipUse> relationships, List<Term> terms,
                          List<DefinitionUse> definitions, List<OrderRule> orderRules,
                          List<DatasetExecution> datasets, Fact metadataSnapshot, Fact excludedColumns, Fact model,
                          Fact units) {

    public static final String RELATIONSHIP_APPROVER_NOT_IN_PLAN = "relationship_approver_not_in_plan";
    public static final String EXCLUDED_COLUMNS_NOT_PROVIDED = "excluded_columns_not_provided";
    public static final String MODEL_NOT_RECORDED = "model_not_recorded_in_specification";
    public static final String UNITS_NOT_IN_CONTRACTS = "units_not_in_contracts";

    public Methodology {
        relations = List.copyOf(relations);
        relationships = List.copyOf(relationships);
        terms = List.copyOf(terms);
        definitions = List.copyOf(definitions);
        orderRules = List.copyOf(orderRules);
        datasets = List.copyOf(datasets);
        Objects.requireNonNull(metadataSnapshot, "metadataSnapshot");
        Objects.requireNonNull(excludedColumns, "excludedColumns");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(units, "units");
    }

    public record RelationshipUse(UUID id, String origin, String status, Fact approvedBy) {
    }

    /**
     * PR3-INT: {@code intent} and {@code evidence} as the specification declares them, and {@code entity} as the key
     * of the approved concept when the plans name it (else its id); all null when the specification does not declare
     * them.
     */
    public record Term(String text, String resolution, String target, String intent, String entity,
                       String evidence) {
        public Term(String text, String resolution, String target) {
            this(text, resolution, target, null, null, null);
        }

        /** Whether the specification declares the evidence of this term. */
        public boolean declared() {
            return evidence != null;
        }
    }

    public record DefinitionUse(String datasetId, String output, GovernedDefinition definition) {
    }

    /** @param collation {@code C} for text keys, {@code null} otherwise */
    public record OrderRule(String datasetId, String output, String direction, String nulls, String collation) {
    }

    public record DatasetExecution(String datasetId, UUID planId, String planFingerprint, int rowCount,
                                   boolean truncated, int maxRows, long durationMs) {
    }
}
