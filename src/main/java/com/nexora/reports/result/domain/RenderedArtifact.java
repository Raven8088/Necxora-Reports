package com.nexora.reports.result.domain;

import java.util.List;
import java.util.Objects;

/**
 * What a renderer produced (A4-B7), in the terms of {@code nexora-report-artifact/1}: media type, sheet names, size
 * and SHA-256 of exactly the bytes delivered (EX-17). Only built for a complete artifact within its limits.
 */
public record RenderedArtifact(String mediaType, String excelContract, List<String> sheets, long sizeBytes,
                               String sha256) {

    public static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String EXCEL_CONTRACT = "nexora-excel-report/1";

    public RenderedArtifact {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(excelContract, "excelContract");
        sheets = List.copyOf(sheets);
        Objects.requireNonNull(sha256, "sha256");
        if (sizeBytes < 1 || sheets.size() < 2) {
            throw new IllegalArgumentException("invalid artifact");
        }
    }
}
