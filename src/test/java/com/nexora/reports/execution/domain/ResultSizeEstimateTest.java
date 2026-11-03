package com.nexora.reports.execution.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4-B10-c.2: the arithmetic of {@link ResultSizeEstimate}. An estimate that does not fit in a {@code long} saturates
 * at {@link Long#MAX_VALUE}, so an overflow can only make a run refused (any budget is below it), never let it through
 * with a smaller figure; negative operands are refused. Extreme values are computed exactly, without overflow.
 */
class ResultSizeEstimateTest {

    @Test
    void aSumThatDoesNotFitSaturatesInsteadOfWrappingAround() {
        assertThat(ResultSizeEstimate.sum(3, 4)).isEqualTo(7L);
        assertThat(ResultSizeEstimate.sum(Long.MAX_VALUE - 1, 10)).isEqualTo(Long.MAX_VALUE);
        assertThat(ResultSizeEstimate.sum(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(ResultSizeEstimate.sum(Long.MAX_VALUE, 0)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aProductThatDoesNotFitSaturatesInsteadOfWrappingAround() {
        assertThat(ResultSizeEstimate.product(4, 5)).isEqualTo(20L);
        assertThat(ResultSizeEstimate.product(0, Long.MAX_VALUE)).isZero();
        assertThat(ResultSizeEstimate.product(Long.MAX_VALUE, 0)).isZero();
        assertThat(ResultSizeEstimate.product(Long.MAX_VALUE / 2 + 1, 2)).isEqualTo(Long.MAX_VALUE);
        assertThat(ResultSizeEstimate.product(8, Integer.MAX_VALUE)).isEqualTo(8L * Integer.MAX_VALUE);
    }

    @Test
    void negativeOperandsAreRefused() {
        assertThatThrownBy(() -> ResultSizeEstimate.sum(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultSizeEstimate.sum(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultSizeEstimate.product(2, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extremeTextsAndNumbersAreEstimatedExactlyWithoutOverflow() {
        String text = "x".repeat(10_000_000);
        assertThat(ResultSizeEstimate.of(text)).isEqualTo(64L + 2L * 10_000_000);

        // The largest numeric PostgreSQL prints: 131 072 digits before the point and 16 383 after.
        BigDecimal largest = new BigDecimal("9".repeat(131_072) + "." + "9".repeat(16_383));
        int bits = largest.unscaledValue().bitLength();
        assertThat(ResultSizeEstimate.of(largest)).isEqualTo(128L + 4L * ((bits + 31L) / 32));
        assertThat(ResultSizeEstimate.of(largest)).isPositive();

        assertThat(ResultSizeEstimate.ofRow(Arrays.asList(text, largest, null)))
                .isEqualTo(96 + 8 * 3 + ResultSizeEstimate.of(text) + ResultSizeEstimate.of(largest));
    }

    @Test
    void everyTypeTheReaderProducesHasItsBound() {
        assertThat(ResultSizeEstimate.of(null)).isZero();
        assertThat(ResultSizeEstimate.of(Boolean.TRUE)).isZero();
        assertThat(ResultSizeEstimate.of(7L)).isEqualTo(24L);
        assertThat(ResultSizeEstimate.of("")).isEqualTo(64L);
        assertThat(ResultSizeEstimate.of(new BigDecimal("1.5"))).isEqualTo(132L);
        assertThat(ResultSizeEstimate.of(LocalDate.of(2026, 11, 2))).isEqualTo(24L);
        assertThat(ResultSizeEstimate.of(LocalDateTime.of(2026, 11, 2, 9, 0))).isEqualTo(80L);
        assertThat(ResultSizeEstimate.of(OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC))).isEqualTo(112L);
        assertThat(ResultSizeEstimate.DATASET).isEqualTo(96L);
    }

    @Test
    void typesTheReaderNeverProducesAreRefused() {
        assertThatThrownBy(() -> ResultSizeEstimate.of(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultSizeEstimate.of(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
