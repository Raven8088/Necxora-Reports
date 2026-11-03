package com.nexora.reports.run.domain;

import java.util.Objects;

/**
 * Who started a run (D-A4-6): the subject of the authenticated principal and whether it is a person or a service. An
 * AI principal never owns a run. B9 takes both from the token; B8 only stores them.
 */
public record RunOwner(String subject, Type type) {

    public static final int MAX_SUBJECT = 255;

    public enum Type { HUMAN, SERVICE }

    public RunOwner {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(type, "type");
        if (subject.isBlank() || subject.length() > MAX_SUBJECT) {
            throw new IllegalArgumentException("invalid owner subject");
        }
    }
}
