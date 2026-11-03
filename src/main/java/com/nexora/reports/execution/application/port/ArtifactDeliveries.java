package com.nexora.reports.execution.application.port;

import com.nexora.reports.run.domain.StoredArtifact;

/**
 * A4-B9 (D-B9-4, adjustment 3 of A4-B8): records what happened when an artifact retrieved from the store was written
 * to an HTTP client. {@code ARTIFACT_DELIVERED} means every byte was written and flushed without error; it does not
 * prove that the client received them. A failure to record throws
 * {@link com.nexora.reports.run.domain.RunStoreException}.
 */
public interface ArtifactDeliveries {

    void delivered(StoredArtifact artifact);

    /** @param detail a stable code, letters, digits and underscores only (for example {@code client_write_failed}) */
    void deliveryFailed(StoredArtifact artifact, String detail);
}
