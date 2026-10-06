package com.frauddetector;

import com.frauddetector.http.Router;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Application entry point. Builds an {@link HttpServer} over
 * {@code com.sun.net.httpserver}, wires a {@link Router}, and starts serving.
 *
 * <p>The listening port defaults to 8080 and can be overridden with the
 * {@code PORT} environment variable. Route wiring lives in
 * {@link #bootstrap(Router)} so later features can register controllers there.
 */
public final class App {

    public static final int DEFAULT_PORT = 8080;

    private App() {
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort();
        Router router = new Router();
        bootstrap(router);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", router);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("Fraud Detector listening on http://localhost:" + port);
    }

    /**
     * Register all routes. Kept small and separate so later features extend the
     * API by adding controller wiring here.
     */
    public static void bootstrap(Router router) {
        router.get("/api/health", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UP");
            return body;
        });
    }

    private static int resolvePort() {
        String env = System.getenv("PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_PORT;
    }
}
