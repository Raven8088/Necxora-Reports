package com.nexora.reports.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B10 (test T-11, additional to T-1…T-8 of the audit): how a body is detected on an operation that takes none.
 * A declared length decides; without one (a body sent in chunks) the first byte is read; an unreadable body counts
 * as present. The behaviour over HTTP, including a real chunked request, is in
 * {@code ReportRunSafeguardsIntegrationTest}.
 */
class RequestBodiesTest {

    @Test
    void aDeclaredLengthDecides() {
        assertThat(RequestBodies.present(request(2, "{}"))).isTrue();
        assertThat(RequestBodies.present(request(0, ""))).isFalse();
    }

    @Test
    void withoutADeclaredLengthTheFirstByteDecides() {
        assertThat(RequestBodies.present(request(-1, "{\"x\":1}"))).isTrue();
        assertThat(RequestBodies.present(request(-1, " "))).isTrue();
        assertThat(RequestBodies.present(request(-1, ""))).isFalse();
    }

    @Test
    void aBodyThatCannotBeReadCountsAsPresent() {
        assertThat(RequestBodies.present(request(-1, null))).isTrue();
    }

    /** A request with the given declared length and body ({@code null}: reading fails). */
    private static HttpServletRequest request(long length, String body) {
        ServletInputStream in = stream(body);
        return (HttpServletRequest) Proxy.newProxyInstance(RequestBodiesTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getContentLengthLong" -> length;
                    case "getContentLength" -> (int) length;
                    case "getInputStream" -> in;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ServletInputStream stream(String body) {
        ByteArrayInputStream bytes = body == null ? null
                : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                if (bytes == null) {
                    throw new IOException("connection reset");
                }
                return bytes.read();
            }

            @Override
            public boolean isFinished() {
                return bytes != null && bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
