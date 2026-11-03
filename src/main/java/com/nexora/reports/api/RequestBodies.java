package com.nexora.reports.api;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * A4-B10 (A4 proposal v3, section 5; D-B10-5): whether a request carries a body, for operations that take none. A
 * declared length decides; without one (a body sent in chunks, {@code Transfer-Encoding: chunked}) the first byte is
 * read. A body that cannot be read counts as present: the check fails closed.
 */
final class RequestBodies {

    private RequestBodies() {
    }

    static boolean present(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length > 0) {
            return true;
        }
        if (length == 0) {
            return false;
        }
        try {
            return request.getInputStream().read() != -1;
        } catch (IOException e) {
            return true;
        }
    }
}
