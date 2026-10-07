package com.frauddetector.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a {@link HttpExchange} and exposes a convenient, framework-style view of
 * the request/response: HTTP method, path, path parameters (populated by the
 * {@link Router}), the parsed JSON request body, query parameters, an
 * authenticated principal (set by later security features), and a
 * {@link #respond(int, Object)} helper that serializes the body with
 * {@link Json} and sets {@code Content-Type: application/json}.
 */
public final class HttpContext {

    private final HttpExchange exchange;
    private final String method;
    private final String path;
    private final Map<String, String> queryParams;
    private Map<String, String> pathParams = new LinkedHashMap<>();
    private Object principal;

    private boolean bodyRead;
    private String rawBody;
    private Map<String, Object> jsonBody;
    private boolean responded;

    public HttpContext(HttpExchange exchange) {
        this.exchange = exchange;
        this.method = exchange.getRequestMethod().toUpperCase();
        this.path = exchange.getRequestURI().getPath();
        this.queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
    }

    public HttpExchange exchange() {
        return exchange;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    // --- path params -----------------------------------------------------

    public Map<String, String> pathParams() {
        return pathParams;
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    void setPathParams(Map<String, String> params) {
        this.pathParams = params;
    }

    // --- query params ----------------------------------------------------

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public String queryParam(String name) {
        return queryParams.get(name);
    }

    // --- principal (auth) ------------------------------------------------

    public Object principal() {
        return principal;
    }

    public void setPrincipal(Object principal) {
        this.principal = principal;
    }

    // --- request body ----------------------------------------------------

    public String rawBody() {
        if (!bodyRead) {
            readBody();
        }
        return rawBody;
    }

    /** Parsed JSON object body, or an empty map if the body is empty/not an object. */
    public Map<String, Object> body() {
        if (jsonBody == null) {
            String raw = rawBody();
            if (raw == null || raw.isBlank()) {
                jsonBody = new LinkedHashMap<>();
            } else {
                jsonBody = Json.parseObject(raw);
            }
        }
        return jsonBody;
    }

    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /** Best-effort remote client address (host:port) for audit logging. */
    public String remoteAddress() {
        try {
            if (exchange.getRemoteAddress() == null) {
                return null;
            }
            return exchange.getRemoteAddress().getAddress() == null
                    ? exchange.getRemoteAddress().toString()
                    : exchange.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private void readBody() {
        bodyRead = true;
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            rawBody = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            rawBody = "";
        }
    }

    // --- response --------------------------------------------------------

    public void respond(int status, Object body) {
        if (responded) {
            return;
        }
        responded = true;
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        } catch (IOException e) {
            // Connection likely closed; nothing further we can do.
        } finally {
            exchange.close();
        }
    }

    /** Send a non-JSON payload (e.g. the static web UI) with an explicit content type. */
    public void respondRaw(int status, String contentType, byte[] payload) {
        if (responded) {
            return;
        }
        responded = true;
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        } catch (IOException e) {
            // Connection likely closed; nothing further we can do.
        } finally {
            exchange.close();
        }
    }

    boolean hasResponded() {
        return responded;
    }

    // --- helpers ---------------------------------------------------------

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return result;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
