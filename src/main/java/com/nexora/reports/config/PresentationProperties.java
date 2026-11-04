package com.nexora.reports.config;

import com.nexora.reports.render.xlsx.PresentationTheme;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Presentation of the Excel reports (D-XL-3, EX-19): the global theme ({@code nexora.reports.presentation.*}). Every
 * value is optional; a missing one is the Nexora theme's. A value that is not valid (a colour that is not RRGGBB, a
 * font outside the allowed list, a size outside 9..14, colours without enough contrast) stops Reports from starting.
 * The theme changes how reports look, never their cells, values or formats. A theme per tenant is not supported yet.
 */
@ConfigurationProperties("nexora.reports.presentation")
public record PresentationProperties(String primaryColor, String headerTextColor, String accentColor,
                                     String bandColor, String fontName, Integer fontSize, Boolean banding) {

    public PresentationProperties {
        // Built (and validated) here, so that a wrong value fails at binding time with its property's name.
        theme(primaryColor, headerTextColor, accentColor, bandColor, fontName, fontSize, banding);
    }

    public PresentationTheme theme() {
        return theme(primaryColor, headerTextColor, accentColor, bandColor, fontName, fontSize, banding);
    }

    private static PresentationTheme theme(String primaryColor, String headerTextColor, String accentColor,
                                           String bandColor, String fontName, Integer fontSize, Boolean banding) {
        return PresentationTheme.of(blank(primaryColor), blank(headerTextColor), blank(accentColor), blank(bandColor),
                blank(fontName), fontSize, banding);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
