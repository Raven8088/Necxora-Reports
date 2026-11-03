package com.nexora.reports.run.domain;

import java.util.Objects;

/**
 * The answer of the store to a request for an artifact (adjustment 3: retrieving from the store is not a download;
 * confirming the delivery to an HTTP client belongs to B9).
 * <ul>
 *   <li>{@link Available}: the bytes, verified (size and SHA-256) before they are handed over;</li>
 *   <li>{@link Gone}: EXPIRED or PURGED (B9: 410);</li>
 *   <li>{@link IntegrityFailed}: the stored bytes do not match their metadata; nothing is handed over;</li>
 *   <li>{@link NotFound}: no such run, or one of another tenant, source or owner; they cannot be told apart.</li>
 * </ul>
 */
public sealed interface Retrieval {

    /** Verified bytes. The array is the caller's: it is not copied (up to 20 MiB). */
    final class Available implements Retrieval {
        private final StoredArtifact artifact;
        private final byte[] content;

        public Available(StoredArtifact artifact, byte[] content) {
            this.artifact = Objects.requireNonNull(artifact, "artifact");
            this.content = Objects.requireNonNull(content, "content");
        }

        public StoredArtifact artifact() {
            return artifact;
        }

        public byte[] content() {
            return content;
        }
    }

    record Gone(StoredArtifact artifact, ArtifactState state) implements Retrieval {
        public Gone {
            Objects.requireNonNull(artifact, "artifact");
            if (state != ArtifactState.EXPIRED && state != ArtifactState.PURGED) {
                throw new IllegalArgumentException("Gone is EXPIRED or PURGED");
            }
        }
    }

    record IntegrityFailed(StoredArtifact artifact) implements Retrieval {
        public IntegrityFailed {
            Objects.requireNonNull(artifact, "artifact");
        }
    }

    record NotFound() implements Retrieval {
    }

    NotFound NOT_FOUND = new NotFound();
}
