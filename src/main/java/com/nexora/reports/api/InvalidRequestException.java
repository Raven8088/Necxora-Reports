package com.nexora.reports.api;

/** A request field outside its allowed range (HTTP 400 {@code validation_failed}). */
public class InvalidRequestException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidRequestException(String field, String reason) {
        super(field + " " + reason);
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
