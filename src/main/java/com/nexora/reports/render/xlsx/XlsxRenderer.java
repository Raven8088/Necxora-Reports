package com.nexora.reports.render.xlsx;

import com.nexora.reports.execution.application.port.ReportRenderer;
import com.nexora.reports.render.layout.LayoutBuilder;
import com.nexora.reports.render.layout.WorkbookLayout;
import com.nexora.reports.result.domain.PresentationException;
import com.nexora.reports.result.domain.RenderLimits;
import com.nexora.reports.result.domain.RenderedArtifact;
import com.nexora.reports.result.domain.ReportResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A4-B7: {@code nexora-excel-report/1} for any {@link ReportResult} (D-B7-1). The layout ({@link LayoutBuilder}) and
 * the writing ({@link XlsxWriter}) are separate (precision 2); this class adds the guarantees:
 * <ul>
 *   <li>at most {@code maxConcurrent} presentations at once, waiting at most {@link RenderLimits#acquireTimeout()}
 *       ({@code too_many_executions} otherwise);</li>
 *   <li>the cells to write never exceed the reserve A4-B6 accepted ({@link ReportResult#presentationCells()},
 *       precision 7), checked on the layout and on what was written;</li>
 *   <li>{@link RenderLimits#maxFileBytes()} (20 MiB) checked first by a lower bound of the size and then on every
 *       byte written (precision 4): a file over the limit is discarded, never returned;</li>
 *   <li>the file is written to a temporary file of its own, digested while written (EX-17), and copied to the
 *       caller's stream only when complete; the temporary file is deleted in every case (precision 8).</li>
 * </ul>
 */
public final class XlsxRenderer implements ReportRenderer {

    public static final int DEFAULT_MAX_CONCURRENT = 4;

    private final Semaphore permits;
    private final Path temporaryDirectory;

    public XlsxRenderer() {
        this(DEFAULT_MAX_CONCURRENT, null);
    }

    /** @param temporaryDirectory where the file is written before it is delivered; {@code null}: the system's. */
    public XlsxRenderer(int maxConcurrent, Path temporaryDirectory) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        this.permits = new Semaphore(maxConcurrent, true);
        this.temporaryDirectory = temporaryDirectory;
    }

    @Override
    public RenderedArtifact render(ReportResult result, OutputStream out, RenderLimits limits) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(limits, "limits");
        acquire(limits);
        try {
            return renderWithPermit(result, out, limits);
        } finally {
            permits.release();
        }
    }

    private void acquire(RenderLimits limits) {
        try {
            if (!permits.tryAcquire(limits.acquireTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                throw new PresentationException(PresentationException.Code.TOO_MANY_PRESENTATIONS,
                        "no presentation slot within " + limits.acquireTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PresentationException(PresentationException.Code.TOO_MANY_PRESENTATIONS,
                    "interrupted while waiting for a presentation slot", e);
        }
    }

    private RenderedArtifact renderWithPermit(ReportResult result, OutputStream out, RenderLimits limits) {
        WorkbookLayout layout = LayoutBuilder.build(result);
        long cells = layout.cellCount();
        if (cells > result.presentationCells()) {
            throw new PresentationException(PresentationException.Code.RENDER_FAILED,
                    "layout of " + cells + " cells over the reserve of " + result.presentationCells());
        }
        if (layout.minimumBytes() > limits.maxFileBytes()) {
            throw new PresentationException(PresentationException.Code.ARTIFACT_TOO_LARGE,
                    "at least " + layout.minimumBytes() + " bytes, over " + limits.maxFileBytes());
        }
        Path file = null;
        LimitedDigestOutputStream counted = null;
        try {
            file = temporaryDirectory == null ? Files.createTempFile("nexora-report-", ".xlsx")
                    : Files.createTempFile(temporaryDirectory, "nexora-report-", ".xlsx");
            long written;
            try (LimitedDigestOutputStream s = new LimitedDigestOutputStream(Files.newOutputStream(file),
                    limits.maxFileBytes())) {
                counted = s;
                written = XlsxWriter.write(layout, result.header().generatedAt(), limits.rowWindow(), s);
            }
            if (written != cells) {
                throw new PresentationException(PresentationException.Code.RENDER_FAILED,
                        "wrote " + written + " cells, the layout has " + cells);
            }
            String sha256 = counted.sha256();
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(out);
            }
            return new RenderedArtifact(RenderedArtifact.XLSX_MEDIA_TYPE, RenderedArtifact.EXCEL_CONTRACT,
                    layout.sheetNames(), counted.count(), sha256);
        } catch (PresentationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if ((counted != null && counted.exceeded()) || causedByLimit(e)) {
                throw new PresentationException(PresentationException.Code.ARTIFACT_TOO_LARGE,
                        "artifact over " + limits.maxFileBytes() + " bytes", e);
            }
            throw new PresentationException(PresentationException.Code.RENDER_FAILED, "the artifact could not be "
                    + "written", e);
        } finally {
            delete(file);
        }
    }

    private static boolean causedByLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof LimitedDigestOutputStream.LimitExceededException) {
                return true;
            }
        }
        return false;
    }

    private static void delete(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                file.toFile().deleteOnExit();
            }
        }
    }
}
