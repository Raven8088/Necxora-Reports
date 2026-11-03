package com.nexora.reports.run.infrastructure;

import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ResultWarning;

import java.util.List;

/**
 * The datasets and warnings of an outcome as JSON arrays for {@code jsonb} columns: ids, counts, flags and codes only.
 * Written here, read back by PostgreSQL itself ({@code jsonb_to_recordset}), so no JSON library is needed.
 */
final class RunJson {

    private RunJson() {
    }

    static String datasets(List<Methodology.DatasetExecution> datasets) {
        StringBuilder b = new StringBuilder("[");
        for (Methodology.DatasetExecution d : datasets) {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append("{\"datasetId\":").append(string(d.datasetId()))
                    .append(",\"planId\":").append(string(String.valueOf(d.planId())))
                    .append(",\"planFingerprint\":").append(string(d.planFingerprint()))
                    .append(",\"rowCount\":").append(d.rowCount())
                    .append(",\"truncated\":").append(d.truncated())
                    .append(",\"maxRows\":").append(d.maxRows())
                    .append(",\"durationMs\":").append(d.durationMs()).append('}');
        }
        return b.append(']').toString();
    }

    static String warnings(List<ResultWarning> warnings) {
        StringBuilder b = new StringBuilder("[");
        for (ResultWarning w : warnings) {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append("{\"code\":").append(string(w.code()))
                    .append(",\"datasetId\":").append(string(w.datasetId()))
                    .append(",\"output\":").append(string(w.output()))
                    .append(",\"delivered\":").append(w.delivered())
                    .append(",\"limit\":").append(w.limit()).append('}');
        }
        return b.append(']').toString();
    }

    /** A JSON string (or {@code null}), with every character that JSON requires escaped. */
    static String string(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
