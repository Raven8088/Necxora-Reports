package com.nexora.reports.render.layout;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The value of one cell, decided by the layout and written as is by the XLSX writer. Every text goes through
 * {@link #text(String)}, which marks texts that a spreadsheet could take for a formula (EX-11, precision 6): data,
 * labels, titles, filters and methodology alike.
 */
public sealed interface CellValue {

    /** Characters that make a spreadsheet read a typed text as a formula or a command. */
    String FORMULA_TRIGGERS = "=+-@\t\r\n";

    /** @param quotePrefix written with the quote prefix, so that editing it never turns it into a formula */
    record Text(String value, boolean quotePrefix) implements CellValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /** @param format a number format of the writer, for example {@code #,##0} */
    record Number(double value, String format) implements CellValue {
        public Number {
            Objects.requireNonNull(format, "format");
        }
    }

    /** A date or date-time of the 1900 date system, already in the zone it is shown in. */
    record DateTime(LocalDateTime value, String format) implements CellValue {
        public DateTime {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(format, "format");
        }
    }

    /** No value: the cell is not written. */
    record Blank() implements CellValue {
    }

    Blank BLANK = new Blank();

    static Text text(String value) {
        return new Text(value, !value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0);
    }
}
