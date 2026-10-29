package com.nexora.reports.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable RFC 7807 error bodies: {@code type}, {@code title}, {@code status}, {@code code}, {@code detail},
 * {@code instance} and {@code correlationId}, plus {@code violations} or {@code errors} when relevant. Details are
 * fixed texts per code: never SQL, values sent, stack traces, credentials or connection details.
 */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {
    }

    public static Map<String, Object> of(HttpStatus status, String code, String detail, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        Object correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return body;
    }

    public static Map<String, Object> with(Map<String, Object> body, String name, List<?> items) {
        body.put(name, items);
        return body;
    }

    public static void write(HttpServletResponse response, HttpStatus status, Map<String, Object> body,
                             ObjectMapper json) throws IOException {
        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON.toString());
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }
}
