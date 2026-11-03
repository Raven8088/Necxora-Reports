package com.nexora.reports.execution.application.port;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * A4-B9: Reports' own directory where an artifact is written between rendering and storing (A4-B8, D-B8-4). Names are
 * never derived from any input. Closing a staged file deletes it, whatever happened.
 */
public interface StagingArea {

    Staged create() throws IOException;

    /** One staged file; {@link #close()} deletes it. */
    interface Staged extends AutoCloseable {

        Path path();

        OutputStream openOutput() throws IOException;

        @Override
        void close() throws IOException;
    }
}
