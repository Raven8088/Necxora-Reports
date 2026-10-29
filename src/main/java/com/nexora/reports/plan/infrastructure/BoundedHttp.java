package com.nexora.reports.plan.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Minimal HTTP client for calls to fixed, operator-configured endpoints (Semantic and the token endpoint). Redirects
 * are never followed and response bodies are bounded, so a misbehaving upstream cannot redirect Reports elsewhere or
 * exhaust its memory.
 */
public class BoundedHttp {

    /** Status and bounded body of a response. */
    public record Response(int status, byte[] body) {
    }

    /** Transport failure (connection refused, timeout, oversized body...). */
    public static class TransportException extends RuntimeException {
        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient client;
    private final Duration readTimeout;
    private final int maxBytes;

    public BoundedHttp(Duration connectTimeout, Duration readTimeout, int maxBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.readTimeout = Objects.requireNonNull(readTimeout);
        this.maxBytes = maxBytes;
    }

    public Response get(URI uri, String bearerToken) {
        return send(HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .GET().build());
    }

    public Response postForm(URI uri, String authorization, String form) {
        return send(HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(maxBytes + 1);
                if (body.length > maxBytes) {
                    throw new TransportException("response body exceeds the limit", null);
                }
                return new Response(response.statusCode(), body);
            }
        } catch (IOException e) {
            throw new TransportException("transport failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("interrupted", e);
        }
    }
}
