package com.nexora.reports.api;

/** A4-B10: a body sent to an operation that takes none (HTTP 400 {@code malformed_request}). */
public class RequestBodyNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestBodyNotAllowedException() {
        super("this operation takes no request body");
    }
}
