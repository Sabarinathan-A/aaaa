package com.frauddetector.controller;

import com.frauddetector.http.ApiException;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the single-page browser test console at {@code GET /}.
 *
 * <p>The page is loaded from the classpath ({@code /web/index.html}, copied from
 * {@code src/main/resources} by the build) and falls back to the source tree so
 * it also works when running straight from {@code out/} without a copy step.
 * The page itself is public; every API call it makes still goes through the
 * normal bearer-token auth and role checks.
 */
public final class WebController {

    private static final String RESOURCE = "/web/index.html";
    private static final Path SOURCE_FALLBACK = Path.of("src", "main", "resources", "web", "index.html");

    public void register(Router router) {
        router.get("/", this::index);
        router.get("/index.html", this::index);
    }

    private Object index(HttpContext ctx) throws IOException {
        byte[] html = load();
        ctx.respondRaw(200, "text/html; charset=utf-8", html);
        return null;
    }

    private static byte[] load() throws IOException {
        try (InputStream in = WebController.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        if (Files.exists(SOURCE_FALLBACK)) {
            return Files.readAllBytes(SOURCE_FALLBACK);
        }
        throw new ApiException(404, "Web UI not found (expected " + RESOURCE + " on the classpath)");
    }
}
