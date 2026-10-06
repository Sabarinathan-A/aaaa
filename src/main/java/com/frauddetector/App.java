package com.frauddetector;

import com.frauddetector.config.Seed;
import com.frauddetector.controller.AuthController;
import com.frauddetector.controller.ClaimController;
import com.frauddetector.http.Router;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.PasswordHasher;
import com.frauddetector.security.TokenService;
import com.frauddetector.service.AuthService;
import com.frauddetector.service.ClaimService;
import com.frauddetector.service.ValidationService;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Default token TTL (seconds) when {@code TOKEN_TTL_SECONDS} is unset. */
    public static final long DEFAULT_TOKEN_TTL_SECONDS = 3600L;

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
     * API by adding controller wiring here. Wires repositories, security,
     * services, controllers, and seeds in-memory data.
     */
    public static void bootstrap(Router router) {
        // Health check (public).
        router.get("/api/health", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UP");
            return body;
        });

        // Repositories (in-memory, thread-safe).
        UserRepository users = new UserRepository();
        PatientRepository patients = new PatientRepository();
        ProviderRepository providers = new ProviderRepository();
        ClaimRepository claims = new ClaimRepository();

        // Security.
        PasswordHasher hasher = new PasswordHasher();
        TokenService tokenService = new TokenService(resolveSecret(), resolveTokenTtl());
        AuthFilter authFilter = new AuthFilter(tokenService);

        // Services.
        ValidationService validation = new ValidationService(patients, providers, claims);
        ClaimService claimService = new ClaimService(claims, validation);
        AuthService authService = new AuthService(users, hasher, tokenService);

        // Controllers.
        new AuthController(authService).register(router);
        new ClaimController(claimService, authFilter).register(router);

        // Seed data + print credentials for manual testing.
        List<Seed.Credential> credentials =
                new Seed(users, patients, providers, claims, hasher).load();
        Seed.printCredentials(credentials);
    }

    private static String resolveSecret() {
        String env = System.getenv("APP_SECRET");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        // Dev-only default; override with APP_SECRET in any real deployment.
        return "fraud-detector-dev-secret-change-me";
    }

    private static long resolveTokenTtl() {
        String env = System.getenv("TOKEN_TTL_SECONDS");
        if (env != null && !env.isBlank()) {
            try {
                return Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_TOKEN_TTL_SECONDS;
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
