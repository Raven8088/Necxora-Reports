package com.nexora.reports.plan2.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A4-B10 (D-A4-8): the {@code nexora-capabilities/1} of Reports ({@code service: reports}), generated from
 * {@link ReportsProfile} and the {@link ReportsCeilings} the validator uses, never written by hand. It announces only
 * what this version implements: profile {@code basic-a}, the contracts listed by the A4 proposal v3 (section 5) and
 * the effective row limit of a plan. Limitations the schema cannot express (mandatory tenancy evidence, output
 * formats, retention) are documented in {@code docs/API.md} (P-4).
 */
public final class ReportsCapabilities {

    public static final String FORMAT = "nexora-capabilities/1";
    public static final String SERVICE = "reports";

    /**
     * Contracts announced, exactly the list of the A4 proposal v3 (section 5). {@code nexora-report-artifact/1} and
     * {@code nexora-report-spec/1} are used too but are not announced until a normative decision says so (B10-b.1).
     */
    public static final List<String> CONTRACTS = List.of("nexora-report-plan/1", "nexora-logical-plan/2",
            "nexora-logical-plan/1", "nexora-report-run/1", "nexora-excel-report/1");

    private final Map<String, Object> document;

    private ReportsCapabilities(Map<String, Object> document) {
        this.document = document;
    }

    /**
     * @param serviceVersion the version of this build (as stated in the report header)
     * @param ceilings       the ceilings the validator enforces
     */
    public static ReportsCapabilities of(String serviceVersion, ReportsCeilings ceilings) {
        Objects.requireNonNull(serviceVersion, "serviceVersion");
        Objects.requireNonNull(ceilings, "ceilings");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", ReportsProfile.BASIC_A);
        // One dataset per logical plan and one plan per dataset (RPLAN-R1 and the identity checks of the validator).
        profile.put("maxDatasets", ceilings.maxPlans());
        profile.put("maxSections", ReportsProfile.MAX_SECTIONS);
        profile.put("maxJoinsPerDataset", ReportsProfile.MAX_JOINS_PER_DATASET);
        profile.put("datasetModes", ReportsProfile.DATASET_MODES);
        profile.put("aggregations", ReportsProfile.AGGREGATIONS);
        profile.put("filterOperators", ReportsProfile.FILTER_OPERATORS);
        profile.put("itemTypes", ReportsProfile.ITEM_TYPES);
        profile.put("compositions", ReportsProfile.COMPOSITIONS);
        profile.put("planOperations", ReportsProfile.PLAN_OPERATIONS);
        profile.put("collations", ReportsProfile.COLLATIONS);
        profile.put("consistency", ReportsProfile.CONSISTENCY);
        profile.put("uniqueness", ReportsProfile.UNIQUENESS);
        // Effective: a plan may ask for fewer rows, never for more than the ceiling of Reports.
        profile.put("maxRowsPerPlan", ceilings.maxRowsPerPlan());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("service", SERVICE);
        document.put("serviceVersion", serviceVersion);
        document.put("contracts", CONTRACTS);
        document.put("profiles", List.of(Collections.unmodifiableMap(profile)));
        return new ReportsCapabilities(Collections.unmodifiableMap(document));
    }

    /** The document, in contract order; immutable. */
    public Map<String, Object> document() {
        return document;
    }
}
