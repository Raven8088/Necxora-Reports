package com.nexora.reports.render.layout;

import com.nexora.reports.execution.domain.OutputValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** A4-B7 (EX-06, EX-10, R-3; precision 5): typed values, precision kept, nulls never 0. */
class ValueFormatTest {

    private final ValueFormat es = new ValueFormat(ZoneId.of("America/Bogota"), "es");

    private ValueFormat.Formatted f(Object value, OutputValueType type) {
        return es.format(value, type, false);
    }

    @Test
    void integersAreNumbersWithoutDecimalsWhileExact() {
        assertThat(f(35L, OutputValueType.INTEGER)).isEqualTo(new ValueFormat.Formatted(
                new CellValue.Number(35, "#,##0"), false));
        long max = 1L << 53;
        assertThat(f(max, OutputValueType.INTEGER).value()).isEqualTo(new CellValue.Number(max, "#,##0"));
        assertThat(f(-max, OutputValueType.INTEGER).asText()).isFalse();
    }

    @Test
    void integersBeyondTheExactRangeOfExcelAreTextFlagged() {
        assertThat(f((1L << 53) + 1, OutputValueType.INTEGER)).isEqualTo(new ValueFormat.Formatted(
                CellValue.text("9007199254740993"), true));
        assertThat(f(Long.MIN_VALUE, OutputValueType.INTEGER)).isEqualTo(new ValueFormat.Formatted(
                new CellValue.Text("-9223372036854775808", true), true));
    }

    @Test
    void decimalsKeepTheirScale() {
        assertThat(f(new BigDecimal("1234.50"), OutputValueType.NUMBER).value())
                .isEqualTo(new CellValue.Number(1234.5, "#,##0.00"));
        assertThat(f(new BigDecimal("0.1"), OutputValueType.NUMBER).value())
                .isEqualTo(new CellValue.Number(0.1, "#,##0.0"));
        assertThat(f(new BigDecimal("12"), OutputValueType.NUMBER).value())
                .isEqualTo(new CellValue.Number(12, "#,##0"));
        assertThat(f(new BigDecimal("1.2E+3"), OutputValueType.NUMBER).value())
                .isEqualTo(new CellValue.Number(1200, "#,##0"));
    }

    @Test
    void decimalsThatExcelCannotHoldExactlyAreTheirExactTextFlagged() {
        assertThat(f(new BigDecimal("12345678901234567"), OutputValueType.NUMBER))
                .isEqualTo(new ValueFormat.Formatted(CellValue.text("12345678901234567"), true));
        assertThat(f(new BigDecimal("0.1234567890123456789"), OutputValueType.NUMBER))
                .isEqualTo(new ValueFormat.Formatted(CellValue.text("0.1234567890123456789"), true));
        assertThat(f(new BigDecimal("1E-400"), OutputValueType.NUMBER).asText()).isTrue();
        assertThat(f(new BigDecimal("-5.25"), OutputValueType.NUMBER).value())
                .isEqualTo(new CellValue.Number(-5.25, "#,##0.00"));
    }

    @Test
    void booleansAreWordsOfTheReportLanguage() {
        assertThat(f(true, OutputValueType.BOOLEAN).value()).isEqualTo(CellValue.text("Sí"));
        assertThat(f(false, OutputValueType.BOOLEAN).value()).isEqualTo(CellValue.text("No"));
        ValueFormat en = new ValueFormat(ZoneId.of("UTC"), "en");
        assertThat(en.format(true, OutputValueType.BOOLEAN, false).value()).isEqualTo(CellValue.text("Yes"));
    }

    @Test
    void datesAndTimestampsAreExcelDatesWithIsoFormats() {
        assertThat(f(LocalDate.of(2026, 11, 1), OutputValueType.DATE).value())
                .isEqualTo(new CellValue.DateTime(LocalDateTime.of(2026, 11, 1, 0, 0), "yyyy-mm-dd"));
        assertThat(f(LocalDateTime.of(2026, 11, 1, 8, 30, 5), OutputValueType.TIMESTAMP).value())
                .isEqualTo(new CellValue.DateTime(LocalDateTime.of(2026, 11, 1, 8, 30, 5), "yyyy-mm-dd hh:mm:ss"));
        assertThat(f(LocalDateTime.of(2026, 11, 1, 8, 30, 5, 120_000_000), OutputValueType.TIMESTAMP).value())
                .isEqualTo(new CellValue.DateTime(LocalDateTime.of(2026, 11, 1, 8, 30, 5, 120_000_000),
                        "yyyy-mm-dd hh:mm:ss.000"));
    }

    @Test
    void datesOutsideExcelOrFinerThanMillisecondsAreTheirIsoTextFlagged() {
        assertThat(f(LocalDate.of(1899, 12, 31), OutputValueType.DATE))
                .isEqualTo(new ValueFormat.Formatted(CellValue.text("1899-12-31"), true));
        assertThat(f(LocalDate.of(10000, 1, 1), OutputValueType.DATE).asText()).isTrue();
        assertThat(f(LocalDateTime.of(2026, 11, 1, 8, 30, 5, 123_456_000), OutputValueType.TIMESTAMP))
                .isEqualTo(new ValueFormat.Formatted(CellValue.text("2026-11-01T08:30:05.123456"), true));
    }

    @Test
    void timestampsWithZoneAreShownInTheZoneOfTheReport() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 11, 1, 18, 0, 0, 0, ZoneOffset.UTC);
        assertThat(f(utc, OutputValueType.TIMESTAMP_TZ).value())
                .isEqualTo(new CellValue.DateTime(LocalDateTime.of(2026, 11, 1, 13, 0), "yyyy-mm-dd hh:mm:ss"));
    }

    @Test
    void nullsAreNoValueForGroupKeysAndEmptyOtherwiseNeverZero() {
        assertThat(es.format(null, OutputValueType.TEXT, true).value()).isEqualTo(CellValue.text("(sin valor)"));
        assertThat(es.format(null, OutputValueType.INTEGER, true).value()).isEqualTo(CellValue.text("(sin valor)"));
        for (OutputValueType type : OutputValueType.values()) {
            assertThat(es.format(null, type, false)).isEqualTo(new ValueFormat.Formatted(CellValue.BLANK, false));
        }
    }

    @Test
    void textsStartingWithAFormulaTriggerAreKeptLiteral() {
        for (String s : new String[] {"=Cuenca-9", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "\nx"}) {
            assertThat(f(s, OutputValueType.TEXT).value()).isEqualTo(new CellValue.Text(s, true));
        }
        assertThat(f("Cuenca=9", OutputValueType.TEXT).value()).isEqualTo(new CellValue.Text("Cuenca=9", false));
        assertThat(f("", OutputValueType.TEXT).value()).isEqualTo(new CellValue.Text("", false));
    }
}
