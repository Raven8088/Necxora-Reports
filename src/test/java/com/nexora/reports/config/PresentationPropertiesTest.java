package com.nexora.reports.config;

import com.nexora.reports.render.xlsx.PresentationTheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-XL-3 (EX-19): {@code nexora.reports.presentation.*}: empty values are the Nexora theme; invalid ones stop. */
class PresentationPropertiesTest {

    @Test
    void emptyOrMissingValuesAreTheNexoraTheme() {
        assertThat(new PresentationProperties(null, null, null, null, null, null, null).theme())
                .isEqualTo(PresentationTheme.DEFAULT);
        assertThat(new PresentationProperties("", " ", "", "", "", null, null).theme())
                .isEqualTo(PresentationTheme.DEFAULT);
    }

    @Test
    void configuredValuesBecomeTheThemeAndInvalidOnesStopReportsFromStarting() {
        PresentationTheme t = new PresentationProperties("2E4053", "FFFFFF", "E8EEF4", "F5F5F5", "Arial", 10, true)
                .theme();
        assertThat(t.primaryColor()).isEqualTo("2E4053");
        assertThat(t.banding()).isTrue();
        assertThatThrownBy(() -> new PresentationProperties("blue", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("primary-color");
        assertThatThrownBy(() -> new PresentationProperties(null, null, null, null, "Wingdings", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
