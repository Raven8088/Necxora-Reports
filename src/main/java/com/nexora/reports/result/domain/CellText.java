package com.nexora.reports.result.domain;

/**
 * The text limit of a cell (precision 4 of A4-B6): at most {@code max} UTF-16 code units, mark included, never
 * splitting a surrogate pair. A cut text ends with {@link #MARK}, so the cut is visible.
 */
public final class CellText {

    /** The visible mark of a cut text: three UTF-16 units ({@code [}, U+2026, {@code ]}). */
    public static final String MARK = "[…]";

    private CellText() {
    }

    /** {@code text} unchanged if it fits; otherwise its longest prefix that fits with the mark, plus the mark. */
    public static String limit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int cut = max - MARK.length();
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1)) && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return text.substring(0, cut) + MARK;
    }

    public static boolean fits(String text, int max) {
        return text == null || text.length() <= max;
    }
}
