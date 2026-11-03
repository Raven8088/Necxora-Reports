package com.nexora.reports.render.layout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B7 (D-B7-2): every fixed text exists in Spanish, English and French; the sheet names are literal. */
class LabelTest {

    private static final List<String> LANGUAGES = List.of("es", "en", "fr");

    @Test
    void everyLabelHasATextInEveryLanguage() {
        for (Label label : Label.values()) {
            for (String language : LANGUAGES) {
                assertThat(label.in(language).isBlank()).as(label + " " + language).isFalse();
            }
        }
    }

    @Test
    void theSheetNamesOfTheContractAreTheSameInEveryLanguage() {
        for (String language : LANGUAGES) {
            assertThat(Label.SHEET_REPORT.in(language)).isEqualTo("Reporte");
            assertThat(Label.SHEET_METHODOLOGY.in(language)).isEqualTo("Metodología");
        }
    }

    @Test
    void theLanguagesAreDistinctAndSpanishIsTheDefault() {
        assertThat(Label.TOTAL.in("es")).isEqualTo("Total general");
        assertThat(Label.TOTAL.in("en")).isEqualTo("Grand total");
        assertThat(Label.TOTAL.in("fr")).isEqualTo("Total général");
        assertThat(Label.TOTAL.in(null)).isEqualTo("Total general");
        assertThat(Label.NO_VALUE.in("EN")).isEqualTo("(no value)");
    }

    @Test
    void argumentsAreReplacedInOrder() {
        assertThat(Label.SEE_SHEET.format("es", "Listado", 35)).isEqualTo("Ver la hoja «Listado» (35 filas).");
        assertThat(Label.SEE_SHEET.format("en", "List", 2)).isEqualTo("See sheet «List» (2 rows).");
    }

    @Test
    void everyReasonOfAnUnavailableDatumOfB6HasItsOwnText() {
        for (String code : List.of("tenant_name_unknown_to_reports", "source_name_not_provided",
                "relationship_approver_not_in_plan", "excluded_columns_not_provided",
                "model_not_recorded_in_specification", "units_not_in_contracts", "metadata_snapshot_not_in_plan")) {
            for (String language : LANGUAGES) {
                assertThat(Label.reason(code, language).contains(code)).as(code).isFalse();
            }
        }
        assertThat(Label.reason("some_new_reason", "es")).isEqualTo("motivo some_new_reason");
    }

    @Test
    void everyOperatorOfTheSpecificationHasAPhrase() {
        for (String operator : List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "IS_NULL", "IS_NOT_NULL")) {
            assertThat(Label.operator(operator).name()).isEqualTo("OP_" + operator);
        }
        assertThatThrownBy(() -> Label.operator("LIKE")).isInstanceOf(IllegalArgumentException.class);
    }
}
