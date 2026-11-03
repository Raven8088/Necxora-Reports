package com.nexora.reports.execution.application.port;

import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;

import java.io.OutputStream;

/**
 * Turns the generic result of a run (A4-B6) into the bytes of one artifact (A4-B7, D-B7-1). It receives only the
 * {@link ReportResult}: it never reads contracts, plans or the data source again. The bytes reach {@code out} only
 * once the artifact is complete and within its limits; on any failure nothing is written to {@code out} and
 * {@link com.nexora.reports.result.domain.PresentationException} is thrown: such an artifact is never valid.
 */
public interface ReportRenderer {

    RenderedArtifact render(ReportResult result, OutputStream out, RenderLimits limits);
}
