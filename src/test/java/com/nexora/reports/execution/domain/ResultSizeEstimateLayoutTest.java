package com.nexora.reports.execution.domain;

import com.nexora.reports.support.MemoryMeasurements;
import com.nexora.reports.support.MemoryMeasurements.Retained;
import com.nexora.reports.translation2.domain.SqlQueryV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B10-c.2, C2-1 (R4-6, v3 §4 bis.5(a), D-C2-1): {@link ResultSizeEstimate} must not be below the memory the rows
 * really retain, measured by JOL on the running JVM ({@link MemoryMeasurements}). Values are built as the reader
 * builds them (fresh strings decoded from bytes, boxed numbers, {@code BigDecimal} from text, UTC instants) and rows
 * are stored through {@link DatasetRows}, exactly as production keeps them.
 * <ul>
 *   <li>values: retained size of the value alone, compared with {@link ResultSizeEstimate#of};</li>
 *   <li>rows ({@code A}): the stored row (its unmodifiable wrapper, list, array and values), compared with
 *       {@link ResultSizeEstimate#ofRow};</li>
 *   <li>dataset ({@code B}): the rows plus the list that holds them.</li>
 * </ul>
 * Tolerance 0. Shared JDK instances are discounted only as {@link MemoryMeasurements} defines; the figure without
 * discount is recorded in {@code target/b10c2-evidence/memoria-tipos-<mode>.json}, one file per reference mode: the
 * second Surefire execution runs this class alone without compressed references. A failure here is a finding about
 * the estimate, not a reason to relax the assertion.
 */
class ResultSizeEstimateLayoutTest {

    private static final Map<String, Object> EVIDENCE = new TreeMap<>();

    static Stream<String> values() {
        return Stream.of("booleano", "entero-en-cache", "entero", "decimal", "decimal-grande", "fecha", "fecha-hora",
                "fecha-hora-en-punto", "instante-utc", "instante-utc-en-punto", "texto-latin1-1", "texto-latin1-9",
                "texto-latin1-32767", "texto-utf16-1", "texto-utf16-9",
                // A4-B10-c.2 correction: texts longer than 18 characters keep a BigInteger even when they fit a long
                "decimal-19-digitos", "decimal-ceros-finales", "decimal-negativo-pequeno", "texto-vacio",
                // extremes: the largest numeric PostgreSQL prints and a text of a million characters
                "decimal-extremo", "texto-latin1-1000000");
    }

    static Stream<Integer> widths() {
        return Stream.of(1, 6, 64);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    void theEstimateOfAValueIsNotBelowWhatItRetains(String kind) {
        Object value = value(kind);
        long estimate = ResultSizeEstimate.of(value);
        Retained retained = measured("valor/" + kind,
                () -> MemoryMeasurements.retained(List.of(value), List.of(value)));
        record("valor/" + kind, estimate, retained);

        assertThat(retained.attributable()).as("%s: retained %s, estimated %s", kind, retained, estimate)
                .isLessThanOrEqualTo(estimate);
    }

    @ParameterizedTest(name = "{0} columnas")
    @MethodSource("widths")
    void theEstimateOfAStoredRowIsNotBelowWhatItRetains(Integer width) {
        DatasetRows dataset = dataset(width, 1);
        List<Object> stored = dataset.rows().getFirst();
        long estimate = ResultSizeEstimate.ofRow(stored);
        Retained retained = measured("fila/" + width, () -> MemoryMeasurements.retained(List.of(stored), stored));
        record("fila/" + width, estimate, retained);

        assertThat(retained.attributable()).as("A, %s columns: retained %s, estimated %s", width, retained, estimate)
                .isLessThanOrEqualTo(estimate);
    }

    @Test
    void theEstimateOfADatasetIsNotBelowWhatItsRowsAndTheirListRetain() {
        DatasetRows dataset = dataset(6, 1_000);
        long estimate = MemoryMeasurements.estimate(List.of(dataset));
        List<Object> values = MemoryMeasurements.values(dataset.rows());
        Retained rows = measured("dataset/6x1000/A",
                () -> MemoryMeasurements.retained(new ArrayList<>(dataset.rows()), values));
        Retained withList = measured("dataset/6x1000/B",
                () -> MemoryMeasurements.retained(List.of(dataset.rows()), values));
        record("dataset/6x1000/A", estimate, rows);
        record("dataset/6x1000/B", estimate, withList);

        assertThat(rows.attributable()).as("A: retained %s, estimated %s", rows, estimate)
                .isLessThanOrEqualTo(estimate);
        assertThat(withList.attributable()).as("B: retained %s, estimated %s", withList, estimate)
                .isLessThanOrEqualTo(estimate);
    }

    /** A4-B10-c.2 correction: a dataset without rows still holds its containers. */
    @Test
    void theEstimateOfAnEmptyDatasetIsNotBelowWhatItsListRetains() {
        DatasetRows dataset = dataset(6, 0);
        long estimate = MemoryMeasurements.estimate(List.of(dataset));
        Retained withList = measured("dataset/6x0/B", () -> MemoryMeasurements.retained(List.of(dataset.rows()),
                List.of()));
        record("dataset/6x0/B", estimate, withList);

        assertThat(estimate).isEqualTo(ResultSizeEstimate.DATASET);
        assertThat(withList.attributable()).as("B: retained %s, estimated %s", withList, estimate)
                .isLessThanOrEqualTo(estimate);
    }

    // ------------------------------------------------------------------ values as the reader builds them

    static Object value(String kind) {
        return switch (kind) {
            case "booleano" -> Boolean.valueOf(true);
            case "entero-en-cache" -> Long.valueOf(7);
            case "entero" -> Long.valueOf(12_345_678_901L);
            case "decimal" -> new BigDecimal(text("1234.50"));
            case "decimal-grande" -> new BigDecimal(text("1234567890123456789012345678901234567890.12"));
            case "fecha" -> LocalDate.of(2026, 11, 2);
            case "fecha-hora" -> LocalDateTime.of(2026, 11, 2, 9, 41, 7, 123_000_000);
            case "fecha-hora-en-punto" -> LocalDateTime.of(2026, 11, 2, 9, 0);
            case "instante-utc" -> OffsetDateTime.of(2026, 11, 2, 14, 41, 7, 123_000_000, ZoneOffset.ofHours(-5))
                    .withOffsetSameInstant(ZoneOffset.UTC);
            case "instante-utc-en-punto" -> OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.ofHours(-5))
                    .withOffsetSameInstant(ZoneOffset.UTC);
            case "texto-latin1-1" -> text("N");
            case "texto-latin1-9" -> text("VOL-00005");
            case "texto-latin1-32767" -> text("x".repeat(32_767));
            case "texto-utf16-1" -> text("€");
            case "texto-utf16-9" -> text("€".repeat(9));
            case "decimal-19-digitos" -> new BigDecimal(text("9000000000000000000"));
            case "decimal-ceros-finales" -> new BigDecimal(text("1.000000000000000000"));
            case "decimal-negativo-pequeno" -> new BigDecimal(text("-0.000123"));
            case "texto-vacio" -> text("");
            case "decimal-extremo" -> new BigDecimal(text("9".repeat(131_072) + "." + "9".repeat(16_383)));
            case "texto-latin1-1000000" -> text("x".repeat(1_000_000));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** A fresh string decoded from bytes, as a JDBC driver produces it (never a constant of the class file). */
    private static String text(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * Rows shaped like the detail of the volume case, built as the reader builds them ({@code new ArrayList<>(width)}
     * then values) and stored by {@link DatasetRows}: column i holds a 9-character text, an 8-character text, a
     * 9-character text, two nulls and a number outside the {@code Long} cache, cyclically.
     */
    static DatasetRows dataset(int width, int rows) {
        List<SqlQueryV2.Output> outputs = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            outputs.add(new SqlQueryV2.Output(i + 1, "c" + (i + 1), "TEXT", "DIMENSION", false));
        }
        List<List<Object>> read = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<Object> row = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                row.add(switch (i % 6) {
                    case 0 -> text("VOL-" + String.format("%05d", 5 * (r + 1)));
                    case 1 -> text("Campo V" + (r % 7));
                    case 2 -> text("Argentina");
                    case 3, 4 -> null;
                    default -> Long.valueOf(100_000L + r);
                });
            }
            read.add(row);
        }
        return new DatasetRows("medida", outputs, read, false, Duration.ZERO);
    }

    /** A measurement that fails is recorded in the evidence (class and message) and the failure is thrown again. */
    private static Retained measured(String key, Supplier<Retained> measurement) {
        try {
            return measurement.get();
        } catch (RuntimeException | Error e) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("error", e.getClass().getName());
            entry.put("message", String.valueOf(e.getMessage()));
            write(key, entry);
            throw e;
        }
    }

    private static synchronized void record(String key, long estimate, Retained retained) {
        Map<String, Object> entry = new LinkedHashMap<>(retained.toMap());
        entry.put("estimateBytes", estimate);
        entry.put("withinEstimate", retained.attributable() <= estimate);
        write(key, entry);
    }

    private static synchronized void write(String key, Map<String, Object> entry) {
        EVIDENCE.put(key, entry);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("test", ResultSizeEstimateLayoutTest.class.getName());
        content.put("jvm", MemoryMeasurements.jvm());
        content.put("cases", EVIDENCE);
        MemoryMeasurements.writeEvidence("memoria-tipos-" + MemoryMeasurements.referenceMode() + ".json", content);
    }
}
