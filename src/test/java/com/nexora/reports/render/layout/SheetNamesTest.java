package com.nexora.reports.render.layout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A4-B7 (EX-01): sheet names Excel accepts, unique ignoring case, the contract's names reserved. */
class SheetNamesTest {

    private final SheetNames names = new SheetNames("Reporte", "Metodología");

    @Test
    void forbiddenCharactersAreReplaced() {
        assertThat(names.unique("Pozos/año: [total]*?\\", "x")).isEqualTo("Pozos año total");
    }

    @Test
    void namesAreCutToThirtyOneCharacters() {
        String name = names.unique("Listado completo de pozos registrados por país", "x");
        assertThat(name).isEqualTo("Listado completo de pozos regis");
        assertThat(name.length()).isEqualTo(31);
    }

    @Test
    void theReservedNamesAndRepeatedNamesGetACounterIgnoringCase() {
        assertThat(names.unique("reporte", "x")).isEqualTo("reporte (2)");
        assertThat(names.unique("METODOLOGÍA", "x")).isEqualTo("METODOLOGÍA (2)");
        assertThat(names.unique("History", "x")).isEqualTo("History (2)");
        assertThat(names.unique("Detalle", "x")).isEqualTo("Detalle");
        assertThat(names.unique("detalle", "x")).isEqualTo("detalle (2)");
        assertThat(names.unique("Detalle", "x")).isEqualTo("Detalle (3)");
    }

    @Test
    void aCounterNeverTakesTheNamePastThirtyOneCharacters() {
        String base = "Listado completo de pozos registrados";
        names.unique(base, "x");
        String second = names.unique(base, "x");
        assertThat(second).isEqualTo("Listado completo de pozos r (2)");
        assertThat(second.length() <= 31).isTrue();
    }

    @Test
    void apostrophesAtTheEndsAreRemoved() {
        assertThat(names.unique("'Pozos'", "x")).isEqualTo("Pozos");
        assertThat(names.unique("Pozos d'Ébano", "x")).isEqualTo("Pozos d'Ébano");
    }

    @Test
    void anEmptyNameFallsBackToTheDatasetId() {
        assertThat(names.unique("***", "detalle_empresas")).isEqualTo("detalle_empresas");
        assertThat(names.unique(null, "detalle")).isEqualTo("detalle");
        assertThat(names.unique("", "")).isEqualTo("Hoja");
    }

    @Test
    void aCutNeverSplitsACharacter() {
        String name = names.unique("a".repeat(30) + "😀", "x");
        assertThat(name).isEqualTo("a".repeat(30));
    }
}
