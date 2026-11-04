package com.nexora.reports.render.xlsx;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Presentation of the Excel reports (D-XL-3, EX-19): the visual theme every report is written with, the same for any
 * company, tenant or source. It holds only colours, a font and switches: what a cell is (its {@code StyleKind}) and
 * what its value is (its {@code ValueKind}) decide how the theme applies; no name or content of a report does.
 * <p>
 * Validated when created, so that a wrong configuration stops Reports from starting instead of producing unreadable
 * files: colours are six hexadecimal digits; the font is one of {@link #FONTS} (installed with Office and with a
 * metric-compatible substitute in LibreOffice); the size is 9 to 14 points; the header text must be legible on the
 * primary colour and black text on the accent and band colours (WCAG contrast of at least 4.5:1). The colours of the
 * warnings are fixed: a warning must look like one in every theme.
 *
 * @param primaryColor    fill of the table headers, colour of titles and of the lines of totals (hex RRGGBB)
 * @param headerTextColor text of the table headers
 * @param accentColor     fill of the totals
 * @param bandColor       fill of every other data row when {@code banding} is on
 * @param fontName        font of every cell
 * @param fontSize        size in points of the data; titles are larger
 * @param banding         alternate row fills (D-XL-2: off by default; bands do not follow a sort or a filter)
 */
public record PresentationTheme(String primaryColor, String headerTextColor, String accentColor, String bandColor,
                                String fontName, int fontSize, boolean banding) {

    public static final Set<String> FONTS = Set.of("Calibri", "Arial", "Aptos");
    public static final double MIN_CONTRAST = 4.5;
    private static final Pattern HEX = Pattern.compile("[0-9A-F]{6}");

    /** The Nexora theme: dark blue headers with white text, light blue totals, Calibri 11, no bands. */
    public static final PresentationTheme DEFAULT = new PresentationTheme("1F3864", "FFFFFF", "D9E1F2", "F2F2F2",
            "Calibri", 11, false);

    public PresentationTheme {
        primaryColor = color("primary-color", primaryColor);
        headerTextColor = color("header-text-color", headerTextColor);
        accentColor = color("accent-color", accentColor);
        bandColor = color("band-color", bandColor);
        Objects.requireNonNull(fontName, "font-name");
        if (!FONTS.contains(fontName)) {
            throw new IllegalArgumentException("presentation font-name must be one of " + FONTS.stream().sorted()
                    .toList());
        }
        if (fontSize < 9 || fontSize > 14) {
            throw new IllegalArgumentException("presentation font-size must be 9..14");
        }
        requireContrast("header-text-color on primary-color", headerTextColor, primaryColor);
        requireContrast("black text on accent-color", "000000", accentColor);
        requireContrast("black text on band-color", "000000", bandColor);
    }

    /** The theme with every value that is {@code null} taken from {@link #DEFAULT}. */
    public static PresentationTheme of(String primaryColor, String headerTextColor, String accentColor,
                                       String bandColor, String fontName, Integer fontSize, Boolean banding) {
        return new PresentationTheme(primaryColor == null ? DEFAULT.primaryColor : primaryColor,
                headerTextColor == null ? DEFAULT.headerTextColor : headerTextColor,
                accentColor == null ? DEFAULT.accentColor : accentColor,
                bandColor == null ? DEFAULT.bandColor : bandColor,
                fontName == null ? DEFAULT.fontName : fontName,
                fontSize == null ? DEFAULT.fontSize : fontSize,
                banding == null ? DEFAULT.banding : banding);
    }

    /** The colour as three bytes. */
    static byte[] rgb(String hex) {
        int v = Integer.parseInt(hex, 16);
        return new byte[] {(byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    /** WCAG 2 contrast ratio of two colours, 1 to 21. */
    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        byte[] c = rgb(hex);
        double[] l = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = (c[i] & 0xFF) / 255.0;
            l[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
    }

    private static String color(String name, String value) {
        String v = value == null ? null : value.strip().toUpperCase(Locale.ROOT);
        if (v != null && v.startsWith("#")) {
            v = v.substring(1);
        }
        if (v == null || !HEX.matcher(v).matches()) {
            throw new IllegalArgumentException("presentation " + name + " must be a colour RRGGBB in hexadecimal");
        }
        return v;
    }

    private static void requireContrast(String what, String text, String fill) {
        if (contrast(text, fill) < MIN_CONTRAST) {
            throw new IllegalArgumentException("presentation colours are not legible: " + what + " has a contrast of "
                    + String.format(Locale.ROOT, "%.2f", contrast(text, fill)) + ", at least " + MIN_CONTRAST
                    + " is required");
        }
    }
}
