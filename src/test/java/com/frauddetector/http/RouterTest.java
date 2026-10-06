package com.frauddetector.http;

import com.frauddetector.testkit.Assert;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Proves {@link Router} path-param extraction plus 404 (no path match) and 405
 * (method mismatch) behavior, using a lightweight fake {@link HttpExchange}.
 * Run via {@code ./build.sh test}.
 */
public final class RouterTest {

    public static void main(String[] args) {
        testPathParamExtraction();
        testMatchDispatch();
        test404();
        test405();
        System.out.println("RouterTest OK");
    }

    private static void testPathParamExtraction() {
        Route route = new Route("GET", "/api/claims/{id}", ctx -> null);
        Assert.assertTrue(route.matchesPath("/api/claims/abc123"), "path matches template");
        Assert.assertFalse(route.matchesPath("/api/claims/abc/extra"), "extra segment does not match");
        Map<String, String> params = route.extractParams("/api/claims/abc123");
        Assert.assertEquals("abc123", params.get("id"), "id param extracted");

        Route two = new Route("GET", "/api/providers/{pid}/claims/{cid}", ctx -> null);
        Map<String, String> p2 = two.extractParams("/api/providers/P1/claims/C9");
        Assert.assertEquals("P1", p2.get("pid"), "pid extracted");
        Assert.assertEquals("C9", p2.get("cid"), "cid extracted");
    }

    private static void testMatchDispatch() {
        Router router = new Router();
        router.get("/api/claims/{id}", ctx -> Map.of("id", ctx.pathParam("id")));

        FakeExchange ex = new FakeExchange("GET", "/api/claims/42", "");
        router.handle(ex);
        Assert.assertEquals(200, ex.statusCode, "matched route returns 200");
        Object parsed = Json.parse(ex.responseText());
        Assert.assertTrue(parsed instanceof Map, "body is object");
        Assert.assertEquals("42", ((Map<?, ?>) parsed).get("id"), "path param flows to handler");
    }

    private static void test404() {
        Router router = new Router();
        router.get("/api/health", ctx -> Map.of("status", "UP"));

        FakeExchange ex = new FakeExchange("GET", "/api/nope", "");
        router.handle(ex);
        Assert.assertEquals(404, ex.statusCode, "unknown path returns 404");
        Map<?, ?> body = (Map<?, ?>) Json.parse(ex.responseText());
        Assert.assertEquals(404L, body.get("status"), "404 status in body");
    }

    private static void test405() {
        Router router = new Router();
        router.get("/api/health", ctx -> Map.of("status", "UP"));

        FakeExchange ex = new FakeExchange("POST", "/api/health", "");
        router.handle(ex);
        Assert.assertEquals(405, ex.statusCode, "method mismatch returns 405");
        Map<?, ?> body = (Map<?, ?>) Json.parse(ex.responseText());
        Assert.assertEquals(405L, body.get("status"), "405 status in body");
    }

    // -----------------------------------------------------------------
    // Minimal in-memory HttpExchange for testing the Router without sockets.
    // -----------------------------------------------------------------
    private static final class FakeExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final InputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        int statusCode = -1;

        FakeExchange(String method, String path, String body) {
            this.method = method;
            this.uri = URI.create(path);
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        String responseText() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            // no-op
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.statusCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
            // no-op
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            // no-op
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
