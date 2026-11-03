package com.nexora.reports.run.domain;

/**
 * The life of an artifact (D-A4-3): AVAILABLE until it expires, EXPIRED until it is purged, PURGED after. Derived from
 * the database clock and the presence of the content, never stored (D-B8-3).
 */
public enum ArtifactState {
    AVAILABLE, EXPIRED, PURGED;

    public static ArtifactState of(boolean contentPresent, boolean expired) {
        if (!contentPresent) {
            return PURGED;
        }
        return expired ? EXPIRED : AVAILABLE;
    }
}
