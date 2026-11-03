package com.nexora.reports.execution.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Conservative estimate of the heap retained by the rows of an execution, checked while the rows are read, because
 * that is where the memory is consumed (D-B4-3). It is an upper bound, not a measurement, of the rows as Reports keeps
 * them ({@link DatasetRows}: an unmodifiable list over an {@code ArrayList} per row, held by the dataset's own list).
 * <p>
 * A4-B10-c.2 (R4-6): the constants are an upper bound for HotSpot 64-bit with the default object alignment of 8
 * bytes, <b>with or without compressed references</b> (8-byte references and 16-byte headers); they are checked by
 * {@code ResultSizeEstimateLayoutTest} with JOL in both modes (B10c2-O3). Other object alignments and other JVMs are
 * not covered. Strings count
 * two bytes per character, whatever their coder. A {@code BigDecimal} always counts its {@code BigInteger}: the JDK
 * keeps one whenever the text it was parsed from is longer than 18 characters, even if the value fits a {@code long}.
 * <p>
 * The estimate bounds the <b>retained rows only</b>: not the copies that exist for a moment while a dataset is built,
 * not the driver's buffers, not the result assembled from the rows nor the rest of the process.
 * <p>
 * Arithmetic saturates at {@link Long#MAX_VALUE} ({@link #sum}): an estimate that does not fit is the largest
 * possible one, so an overflow can only make a run refused, never let it through.
 */
public final class ResultSizeEstimate {

    /** One dataset: its unmodifiable list, its {@code ArrayList} and the header of the array of its rows. */
    public static final long DATASET = 96;
    /** One row: its unmodifiable list, its {@code ArrayList}, the header of its array, its slot in the dataset. */
    static final long ROW = 96;
    /** One reference (a value's slot in its row). */
    static final long REFERENCE = 8;
    /** A string object and the header of its byte array; then two bytes per character. */
    static final long TEXT = 64;
    /** A {@code BigDecimal}, its {@code BigInteger} and the header of its {@code int[]}; then four bytes per int. */
    static final long DECIMAL = 128;
    static final long INTEGER = 24;
    static final long DATE = 24;
    static final long DATE_TIME = 80;
    static final long INSTANT = 112;

    private ResultSizeEstimate() {
    }

    /** One stored row: its containers plus its values. */
    public static long ofRow(List<Object> row) {
        long bytes = sum(ROW, product(REFERENCE, row.size()));
        for (Object value : row) {
            bytes = sum(bytes, of(value));
        }
        return bytes;
    }

    public static long of(Object value) {
        return switch (value) {
            case null -> 0;
            case String s -> sum(TEXT, product(2, s.length()));
            case BigDecimal d -> sum(DECIMAL, product(4, ((long) Math.max(1, d.unscaledValue().bitLength()) + 31) / 32));
            case Long l -> INTEGER;
            case Boolean b -> 0;
            case LocalDate d -> DATE;
            case LocalDateTime t -> DATE_TIME;
            case OffsetDateTime t -> INSTANT;
            default -> throw new IllegalArgumentException("unexpected value type " + value.getClass().getName());
        };
    }

    /** {@code a + b} of two non-negative estimates, saturating at {@link Long#MAX_VALUE}. */
    public static long sum(long a, long b) {
        requireNonNegative(a, b);
        long result = a + b;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    /** {@code a × b} of two non-negative numbers, saturating at {@link Long#MAX_VALUE}. */
    static long product(long a, long b) {
        requireNonNegative(a, b);
        return b != 0 && a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static void requireNonNegative(long a, long b) {
        if (a < 0 || b < 0) {
            throw new IllegalArgumentException("an estimate is never negative");
        }
    }
}
