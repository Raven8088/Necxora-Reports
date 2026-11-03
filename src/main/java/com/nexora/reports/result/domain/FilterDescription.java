package com.nexora.reports.result.domain;

import java.util.List;
import java.util.Objects;

/**
 * A filter of the report, for the header (EX-02): the presentation turns it into a phrase in the report's language.
 *
 * @param columnLabel the label of the select item over the same column, or {@code null} (then the object is shown)
 */
public record FilterDescription(String datasetId, String columnLabel, SourceObject sourceObject, String operator,
                                List<SpecPresentation.Literal> values, String literalSource) {

    public FilterDescription {
        Objects.requireNonNull(datasetId, "datasetId");
        Objects.requireNonNull(sourceObject, "sourceObject");
        Objects.requireNonNull(operator, "operator");
        values = List.copyOf(values);
    }
}
