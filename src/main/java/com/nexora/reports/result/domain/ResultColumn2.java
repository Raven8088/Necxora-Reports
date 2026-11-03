package com.nexora.reports.result.domain;

import com.nexora.reports.execution.domain.OutputValueType;

import java.util.Objects;

/**
 * One column of a dataset result, in the plan's output order (EX-05): the header is the plan's {@code label}; hidden
 * columns (such as {@code row_key}) are kept, because they order the rows, but are never shown.
 *
 * @param position     1-based position in the plan's output and in every row
 * @param sourceObject the physical column the output comes from, or {@code null} for an aggregate without column
 * @param definition   the governed definition, or {@code null}
 */
public record ResultColumn2(int position, String output, String label, OutputValueType valueType, String role,
                            String origin, boolean hidden, SourceObject sourceObject, GovernedDefinition definition) {

    public ResultColumn2 {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(origin, "origin");
        if (position < 1) {
            throw new IllegalArgumentException("position must be positive");
        }
    }
}
