package com.frauddetector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal router over {@link com.sun.net.httpserver}. Routes are registered with
 * an HTTP method + path template and matched against incoming requests:
 * <ul>
 *   <li>no path match -&gt; 404</li>
 *   <li>path matches but method does not -&gt; 405</li>
 *   <li>{@link ApiException} thrown by a handler -&gt; its status + message</li>
 *   <li>any other exception -&gt; 500</li>
 * </ul>
 * A handler may return a value (serialized with HTTP 200) or respond directly
 * via {@link HttpContext#respond(int, Object)}.
 */
public final class Router implements HttpHandler {

    private final List<Route> routes = new ArrayList<>();

    public Router register(String method, String template, Route.Handler handler) {
        routes.add(new Route(method, template, handler));
        return this;
    }

    public Router get(String template, Route.Handler handler) {
        return register("GET", template, handler);
    }

    public Router post(String template, Route.Handler handler) {
        return register("POST", template, handler);
    }

    public Router put(String template, Route.Handler handler) {
        return register("PUT", template, handler);
    }

    public Router delete(String template, Route.Handler handler) {
        return register("DELETE", template, handler);
    }

    public Router patch(String template, Route.Handler handler) {
        return register("PATCH", template, handler);
    }

    @Override
    public void handle(HttpExchange exchange) {
        HttpContext ctx = new HttpContext(exchange);
        try {
            dispatch(ctx);
        } catch (ApiException e) {
            respondError(ctx, e.getStatusCode(), e.getMessage());
        } catch (Exception e) {
            respondError(ctx, 500, e.getMessage() == null ? "Internal Server Error" : e.getMessage());
        }
    }

    private void dispatch(HttpContext ctx) throws Exception {
        boolean pathMatched = false;
        for (Route route : routes) {
            if (route.matchesPath(ctx.path())) {
                pathMatched = true;
                if (route.method().equals(ctx.method())) {
                    ctx.setPathParams(route.extractParams(ctx.path()));
                    Object result = route.handler().handle(ctx);
                    if (!ctx.hasResponded()) {
                        ctx.respond(200, result);
                    }
                    return;
                }
            }
        }
        if (pathMatched) {
            respondError(ctx, 405, "Method Not Allowed");
        } else {
            respondError(ctx, 404, "Not Found");
        }
    }

    private void respondError(HttpContext ctx, int status, String message) {
        if (ctx.hasResponded()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status);
        ctx.respond(status, body);
    }
}
