package com.nexora.reports.render.layout;

import com.nexora.reports.execution.domain.OutputValueType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * How each typed value of the result is written (EX-06, EX-10, R-3, D-B7-4; precision 5):
 * <ul>
 *   <li>INTEGER: a number with {@code #,##0} while it is exact in a double ({@code |v| ≤ 2^53}); otherwise text;</li>
 *   <li>NUMBER: a number with as many decimals as its scale, while it has at most 15 significant digits and the
 *       double reads back as the same decimal; otherwise its exact text;</li>
 *   <li>BOOLEAN: «Sí»/«No» in the report's language;</li>
 *   <li>DATE, TIMESTAMP: dates of the 1900 system with ISO formats; TIMESTAMP_TZ converted to the report's zone;
 *       before 1900, after 9999 or with sub-millisecond precision, their ISO text;</li>
 *   <li>null: «(sin valor)» for a group key of an aggregate, empty otherwise (never 0).</li>
 * </ul>
 * A value written as text to keep its precision is reported by {@link Formatted#asText()}, for the warning.
 */
public final class ValueFormat {

    public static final String INTEGER_FORMAT = "#,##0";
    public static final String DATE_FORMAT = "yyyy-mm-dd";
    public static final String DATE_TIME_FORMAT = "yyyy-mm-dd hh:mm:ss";
    public static final String DATE_TIME_MILLIS_FORMAT = "yyyy-mm-dd hh:mm:ss.000";
    static final long MAX_EXACT_INTEGER = 1L << 53;
    static final int MAX_SIGNIFICANT_DIGITS = 15;
    private static final LocalDateTime EXCEL_MIN = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime EXCEL_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_000_000);

    private final ZoneId zone;
    private final String language;

    public ValueFormat(ZoneId zone, String language) {
        this.zone = zone;
        this.language = language;
    }

    public record Formatted(CellValue value, boolean asText) {
    }

    public Formatted format(Object value, OutputValueType type, boolean groupKey) {
        if (value == null) {
            return new Formatted(groupKey ? CellValue.text(Label.NO_VALUE.in(language)) : CellValue.BLANK, false);
        }
        return switch (type) {
            case TEXT -> new Formatted(CellValue.text((String) value), false);
            case BOOLEAN -> new Formatted(CellValue.text(((Boolean) value ? Label.YES : Label.NO).in(language)),
                    false);
            case INTEGER -> {
                long v = (Long) value;
                yield Math.abs(v) <= MAX_EXACT_INTEGER && v != Long.MIN_VALUE
                        ? new Formatted(new CellValue.Number(v, INTEGER_FORMAT), false)
                        : new Formatted(CellValue.text(Long.toString(v)), true);
            }
            case NUMBER -> number((BigDecimal) value);
            case DATE -> dateTime(((LocalDate) value).atStartOfDay(), DATE_FORMAT, value.toString());
            case TIMESTAMP -> dateTime((LocalDateTime) value, null, value.toString());
            case TIMESTAMP_TZ -> {
                LocalDateTime local = ((OffsetDateTime) value).atZoneSameInstant(zone).toLocalDateTime();
                yield dateTime(local, null, local.toString());
            }
        };
    }

    /**
     * The category of the values of a column of {@code type} (EX-19); a group key that is null is
     * {@link ValueKind#NO_VALUE}. It only steers the presentation: the value and its format are {@link #format}'s.
     */
    public static ValueKind kindOf(OutputValueType type) {
        return switch (type) {
            case TEXT -> ValueKind.TEXT;
            case INTEGER -> ValueKind.INTEGER;
            case NUMBER -> ValueKind.DECIMAL;
            case BOOLEAN -> ValueKind.BOOLEAN;
            case DATE -> ValueKind.DATE;
            case TIMESTAMP, TIMESTAMP_TZ -> ValueKind.DATE_TIME;
        };
    }

    static String numberFormat(int scale) {
        return scale <= 0 ? INTEGER_FORMAT : INTEGER_FORMAT + "." + "0".repeat(Math.min(scale, MAX_SIGNIFICANT_DIGITS));
    }

    private Formatted number(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        double d = v.doubleValue();
        boolean exact = stripped.precision() <= MAX_SIGNIFICANT_DIGITS && Double.isFinite(d)
                && BigDecimal.valueOf(d).compareTo(v) == 0;
        if (!exact) {
            return new Formatted(CellValue.text(v.toPlainString()), true);
        }
        return new Formatted(new CellValue.Number(d, numberFormat(Math.max(0, v.scale()))), false);
    }

    private static Formatted dateTime(LocalDateTime v, String format, String iso) {
        if (v.isBefore(EXCEL_MIN) || v.isAfter(EXCEL_MAX) || v.getNano() % 1_000_000 != 0) {
            return new Formatted(CellValue.text(iso), true);
        }
        String f = format != null ? format : v.getNano() == 0 ? DATE_TIME_FORMAT : DATE_TIME_MILLIS_FORMAT;
        return new Formatted(new CellValue.DateTime(v, f), false);
    }
}
