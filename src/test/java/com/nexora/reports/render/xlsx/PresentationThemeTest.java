package com.nexora.reports.render.xlsx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-XL-3 (EX-19): the theme is validated strictly when it is created, so a wrong configuration never starts. */
class PresentationThemeTest {

    @Test
    void theNexoraThemeIsTheDefaultAndMissingValuesTakeIt() {
        assertThat(PresentationTheme.of(null, null, null, null, null, null, null)).isEqualTo(PresentationTheme.DEFAULT);
        assertThat(PresentationTheme.DEFAULT.banding()).isFalse();
        assertThat(PresentationTheme.DEFAULT.fontName()).isEqualTo("Calibri");
        assertThat(PresentationTheme.of("#7b2d26", null, null, null, null, null, null).primaryColor())
                .isEqualTo("7B2D26");
    }

    @Test
    void coloursMustBeSixHexadecimalDigits() {
        for (String bad : new String[] {"", "red", "12345", "1234567", "GGGGGG", "#12345", "1F 386", "0x1F3864"}) {
            assertThatThrownBy(() -> PresentationTheme.of(bad, null, null, null, null, null, null)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("primary-color");
        }
        assertThatThrownBy(() -> PresentationTheme.of(null, null, "nope", null, null, null, null))
                .hasMessageContaining("accent-color");
    }

    @Test
    void onlyKnownFontsAndSizesFromNineToFourteen() {
        assertThatThrownBy(() -> PresentationTheme.of(null, null, null, null, "Comic Sans MS", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("font-name");
        assertThatThrownBy(() -> PresentationTheme.of(null, null, null, null, "calibri", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PresentationTheme.of(null, null, null, null, null, 8, null))
                .hasMessageContaining("font-size");
        assertThatThrownBy(() -> PresentationTheme.of(null, null, null, null, null, 15, null))
                .hasMessageContaining("font-size");
        assertThat(PresentationTheme.of(null, null, null, null, "Aptos", 9, true).fontSize()).isEqualTo(9);
    }

    @Test
    void coloursWithoutEnoughContrastAreRefused() {
        assertThatThrownBy(() -> PresentationTheme.of("DDDDDD", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header-text-color on primary");
        assertThatThrownBy(() -> PresentationTheme.of(null, null, "333333", null, null, null, null))
                .hasMessageContaining("accent-color");
        assertThatThrownBy(() -> PresentationTheme.of(null, null, null, "555555", null, null, null))
                .hasMessageContaining("band-color");
        assertThat(PresentationTheme.contrast("FFFFFF", "000000")).isEqualTo(21.0);
        assertThat(PresentationTheme.contrast("FFFFFF", "1F3864")).isGreaterThan(11);
        // The fixed colours of the theme are legible too.
        assertThat(PresentationTheme.contrast(XlsxStyles.WARNING_TEXT, XlsxStyles.WARNING_FILL))
                .isGreaterThanOrEqualTo(PresentationTheme.MIN_CONTRAST);
        assertThat(PresentationTheme.contrast(XlsxStyles.NO_VALUE_COLOR, "FFFFFF"))
                .isGreaterThanOrEqualTo(PresentationTheme.MIN_CONTRAST);
        assertThat(PresentationTheme.contrast(XlsxStyles.LABEL_COLOR, "FFFFFF"))
                .isGreaterThanOrEqualTo(PresentationTheme.MIN_CONTRAST);
    }
}
