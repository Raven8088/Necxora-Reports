package com.nexora.reports.render.xlsx;

import com.nexora.reports.render.layout.StyleKind;
import com.nexora.reports.render.layout.ValueKind;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide;

import java.util.HashMap;
import java.util.Map;

/**
 * Presentation of the Excel reports (EX-19): the cell styles of one workbook, built from a {@link PresentationTheme}
 * once per combination of (kind of cell, category of value, number format, quote prefix, band) and cached, so that a
 * report with a million cells still has a few dozen styles (a workbook allows at most 64 000). A style never changes
 * a value or its number format: the format is the layout's, and the quote prefix of a text that could be taken for a
 * formula (EX-11) is part of the key.
 * <p>
 * Kinds of cell: the title is large and in the primary colour; section and table titles in the primary colour, a
 * section with a line under it; the labels of the header in dark grey bold; table headers bold in the header-text
 * colour on the primary colour, wrapped, with thin borders; data with thin light-grey borders; a null group key
 * «(sin valor)» in grey italics; totals bold on the accent colour between a double line above and a medium line
 * below; warnings (and a partial status) dark amber bold on light amber; notes in grey italics. In a table, texts
 * are aligned left, numbers right, dates and booleans centred, the header like its column.
 */
final class XlsxStyles {

    static final String LABEL_COLOR = "595959";
    static final String NOTE_COLOR = "595959";
    static final String NO_VALUE_COLOR = "6B6B6B";
    static final String GRID_COLOR = "BFBFBF";
    static final String WARNING_TEXT = "9C5700";
    static final String WARNING_FILL = "FFEB9C";
    static final String TEXT_COLOR = "000000";

    private final SXSSFWorkbook workbook;
    private final PresentationTheme theme;
    private final DataFormat formats;
    /** Number format (or "" for none) → styles by {@link #slot}: no object is created to look a style up. */
    private final Map<String, XSSFCellStyle[]> styles = new HashMap<>();
    private int count;
    private final Map<String, XSSFFont> fonts = new HashMap<>();
    private final DefaultIndexedColorMap colorMap = new DefaultIndexedColorMap();

    XlsxStyles(SXSSFWorkbook workbook, PresentationTheme theme) {
        this.workbook = workbook;
        this.theme = theme;
        this.formats = workbook.createDataFormat();
        // The default font (empty cells, the unit of column widths) is the theme's too.
        XSSFFont normal = workbook.getXSSFWorkbook().getFontAt(0);
        normal.setFontName(theme.fontName());
        normal.setFontHeightInPoints((short) theme.fontSize());
    }

    PresentationTheme theme() {
        return theme;
    }

    /** The height in points of one line of text of the theme's size (Excel: 15 points for 11). */
    float lineHeight() {
        return Math.round(theme.fontSize() * 15f / 11f * 4) / 4f;
    }

    /** The number of styles created so far. */
    int count() {
        return count;
    }

    XSSFCellStyle get(StyleKind kind, ValueKind value, String format, boolean quotePrefix, boolean band) {
        XSSFCellStyle[] byFormat = styles.computeIfAbsent(format == null ? "" : format,
                f -> new XSSFCellStyle[StyleKind.values().length * ValueKind.values().length * 4]);
        int slot = slot(kind, value, quotePrefix, band);
        XSSFCellStyle style = byFormat[slot];
        if (style == null) {
            style = create(kind, value, format, quotePrefix, band);
            byFormat[slot] = style;
            count++;
        }
        return style;
    }

    private static int slot(StyleKind kind, ValueKind value, boolean quotePrefix, boolean band) {
        return ((kind.ordinal() * ValueKind.values().length + value.ordinal()) * 2 + (quotePrefix ? 1 : 0)) * 2
                + (band ? 1 : 0);
    }

    private XSSFCellStyle create(StyleKind kind, ValueKind value, String format, boolean quotePrefix, boolean band) {
        XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
        if (format != null) {
            style.setDataFormat(formats.getFormat(format));
        }
        style.setQuotePrefixed(quotePrefix);
        int size = theme.fontSize();
        switch (kind) {
            case TITLE -> style.setFont(font(size + 5, true, false, theme.primaryColor()));
            case SECTION_TITLE -> {
                style.setFont(font(size + 2, true, false, theme.primaryColor()));
                border(style, BorderSide.BOTTOM, BorderStyle.MEDIUM, theme.primaryColor());
            }
            case ITEM_TITLE -> style.setFont(font(size, true, false, theme.primaryColor()));
            case LABEL -> style.setFont(font(size, true, false, LABEL_COLOR));
            case VALUE, HEADER_VALUE -> style.setFont(font(size, false, false, TEXT_COLOR));
            case NOTE -> style.setFont(font(size, false, true, NOTE_COLOR));
            case WARNING -> {
                style.setFont(font(size, true, false, WARNING_TEXT));
                fill(style, WARNING_FILL);
            }
            case TABLE_HEADER -> {
                style.setFont(font(size, true, false, theme.headerTextColor()));
                fill(style, theme.primaryColor());
                grid(style);
                style.setWrapText(true);
            }
            case DATA -> {
                style.setFont(value == ValueKind.NO_VALUE ? font(size, false, true, NO_VALUE_COLOR)
                        : font(size, false, false, TEXT_COLOR));
                grid(style);
                if (band) {
                    fill(style, theme.bandColor());
                }
            }
            case TOTAL_LABEL, TOTAL_VALUE -> {
                style.setFont(font(size, true, false, TEXT_COLOR));
                fill(style, theme.accentColor());
                grid(style);
                border(style, BorderSide.TOP, BorderStyle.DOUBLE, theme.primaryColor());
                border(style, BorderSide.BOTTOM, BorderStyle.MEDIUM, theme.primaryColor());
            }
        }
        if (kind.inTable()) {
            style.setAlignment(alignment(value));
            style.setVerticalAlignment(VerticalAlignment.CENTER);
        }
        return style;
    }

    static HorizontalAlignment alignment(ValueKind value) {
        return switch (value) {
            case TEXT, NO_VALUE -> HorizontalAlignment.LEFT;
            case INTEGER, DECIMAL -> HorizontalAlignment.RIGHT;
            case DATE, DATE_TIME, BOOLEAN -> HorizontalAlignment.CENTER;
        };
    }

    private void grid(XSSFCellStyle style) {
        for (BorderSide side : BorderSide.values()) {
            border(style, side, BorderStyle.THIN, GRID_COLOR);
        }
    }

    private void border(XSSFCellStyle style, BorderSide side, BorderStyle line, String color) {
        XSSFColor c = color(color);
        switch (side) {
            case TOP -> {
                style.setBorderTop(line);
                style.setTopBorderColor(c);
            }
            case BOTTOM -> {
                style.setBorderBottom(line);
                style.setBottomBorderColor(c);
            }
            case LEFT -> {
                style.setBorderLeft(line);
                style.setLeftBorderColor(c);
            }
            case RIGHT -> {
                style.setBorderRight(line);
                style.setRightBorderColor(c);
            }
            default -> {
                // DIAGONAL: never drawn.
            }
        }
    }

    private void fill(XSSFCellStyle style, String color) {
        style.setFillForegroundColor(color(color));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    private XSSFFont font(int size, boolean bold, boolean italic, String color) {
        return fonts.computeIfAbsent(size + "|" + bold + "|" + italic + "|" + color, k -> {
            XSSFFont f = (XSSFFont) workbook.createFont();
            f.setFontName(theme.fontName());
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setItalic(italic);
            f.setColor(color(color));
            return f;
        });
    }

    private XSSFColor color(String hex) {
        return new XSSFColor(PresentationTheme.rgb(hex), colorMap);
    }
}
