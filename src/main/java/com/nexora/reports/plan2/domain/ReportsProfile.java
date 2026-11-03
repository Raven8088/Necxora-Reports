package com.nexora.reports.plan2.domain;

import java.util.List;

/**
 * A4-B10 (D-A4-8, D-B10-4): the single source of what Reports accepts in profile {@code basic-a}. The validator
 * ({@link BasicAProfile}, {@link PlanV2Validator} with {@link ReportsCeilings}) enforces these values and the
 * capabilities descriptor ({@link ReportsCapabilities}) announces exactly them, so what is announced is what is
 * accepted. Values that come from the contracts themselves (dataset modes, consistency) are listed here because the
 * schemas Reports validates against admit nothing else.
 * <p>
 * Where each value is enforced: the schema of {@code nexora-report-spec/1} limits, for {@code basic-a}, the datasets
 * (3), the joins of a dataset (2), the sections (1), the item types, the aggregations and the compositions
 * ({@code contract_invalid}); {@link BasicAProfile} limits the logical plans (operations, aggregations, operators,
 * collation, uniqueness and, since A4-B10, the JOIN operations of a plan: {@code plan_not_supported}); the ceilings
 * limit rows and plans ({@code budget_exceeded}); the assembler refuses item types and compositions again
 * ({@code report_layout_invalid}, A4-B6, D-A4-7). Conformance tests check that every place agrees with this class.
 */
public final class ReportsProfile {

    /** The only profile Reports executes. */
    public static final String BASIC_A = "basic-a";

    /** Operations of {@code nexora-logical-plan/2} Reports executes (no {@code GROUP_FILTER}, no {@code SCOPE}). */
    public static final List<String> PLAN_OPERATIONS =
            List.of("SCAN", "JOIN", "FILTER", "AGGREGATE", "PROJECT", "ORDER", "LIMIT");
    public static final List<String> FILTER_OPERATORS =
            List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "IS_NULL", "IS_NOT_NULL");
    public static final List<String> AGGREGATIONS = List.of("COUNT");
    public static final List<String> DATASET_MODES = List.of("DETAIL", "AGGREGATE");
    public static final List<String> ITEM_TYPES = List.of("table", "summaryTable");
    public static final List<String> COMPOSITIONS = List.of();
    /** Byte order: the only collation of text keys. */
    public static final String COLLATION = "C";
    public static final List<String> COLLATIONS = List.of(COLLATION);
    public static final List<String> CONSISTENCY = List.of("SINGLE_READ_ONLY_TRANSACTION/REPEATABLE_READ");
    /** Joins reach exactly the primary key of the joined table. */
    public static final String PRIMARY_KEY = "PRIMARY_KEY";
    public static final List<String> UNIQUENESS = List.of(PRIMARY_KEY);
    /** Sections of a specification ({@code basic-a}; limited by the schema of the specification). */
    public static final int MAX_SECTIONS = 1;
    /** Joins of a dataset: limited by the schema of the specification and, for logical plans, by BasicAProfile. */
    public static final int MAX_JOINS_PER_DATASET = 2;

    private ReportsProfile() {
    }
}
