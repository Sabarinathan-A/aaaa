package com.frauddetector;

import com.frauddetector.config.Seed;
import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.controller.AuditController;
import com.frauddetector.controller.AuthController;
import com.frauddetector.controller.ClaimController;
import com.frauddetector.controller.DashboardController;
import com.frauddetector.controller.InvestigationController;
import com.frauddetector.controller.NotificationController;
import com.frauddetector.controller.ProviderController;
import com.frauddetector.controller.ReportController;
import com.frauddetector.http.Router;
import com.frauddetector.repository.AuditLogRepository;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.NotificationRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.PasswordHasher;
import com.frauddetector.security.TokenService;
import com.frauddetector.service.AuditService;
import com.frauddetector.service.AuthService;
import com.frauddetector.service.ClaimService;
import com.frauddetector.service.DashboardService;
import com.frauddetector.service.FraudRiskService;
import com.frauddetector.service.InvestigationService;
import com.frauddetector.service.NotificationService;
import com.frauddetector.service.ProviderService;
import com.frauddetector.service.ReportService;
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
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        InvestigationRepository investigations = new InvestigationRepository();
        AuditLogRepository auditLogs = new AuditLogRepository();
        NotificationRepository notifications = new NotificationRepository();

        // Security.
        PasswordHasher hasher = new PasswordHasher();
        TokenService tokenService = new TokenService(resolveSecret(), resolveTokenTtl());
        AuthFilter authFilter = new AuthFilter(tokenService);

        // Services.
        ThresholdConfig thresholds = ThresholdConfig.fromEnv();
        ValidationService validation = new ValidationService(patients, providers, claims);
        FraudRiskService fraudRiskService =
                new FraudRiskService(claims, patients, analyses, thresholds);
        AuditService auditService = new AuditService(auditLogs);
        NotificationService notificationService = new NotificationService(notifications);
        ClaimService claimService = new ClaimService(claims, validation, fraudRiskService, analyses);
        claimService.setNotificationService(notificationService);
        claimService.setInvestigationRepository(investigations);
        AuthService authService = new AuthService(users, hasher, tokenService);
        InvestigationService investigationService = new InvestigationService(investigations, claims);
        DashboardService dashboardService =
                new DashboardService(claims, analyses, providers, thresholds);
        ReportService reportService =
                new ReportService(claims, analyses, providers, investigations, thresholds);
        ProviderService providerService = new ProviderService(providers, claims);

        // Controllers. InvestigationController + ReportController register literal
        // routes (/api/claims/high-risk, /api/reports/fraud-analytics) that must
        // come before the templated controllers so the literal match wins.
        new AuthController(authService, auditService).register(router);
        new InvestigationController(investigationService, claimService, authFilter, auditService)
                .register(router);
        new ClaimController(claimService, authFilter, auditService).register(router);
        new DashboardController(dashboardService, authFilter).register(router);
        new AuditController(auditService, authFilter).register(router);
        new ReportController(reportService, authFilter).register(router);
        new NotificationController(notificationService, authFilter).register(router);
        new ProviderController(providerService, authFilter).register(router);

        // Seed demo data only under the dev profile. The seed creates four
        // fixed accounts with known passwords printed to stdout, which is fine
        // for the sandbox demo but must never run in a real deployment (review
        // finding #4). In a non-dev profile, start with empty stores so no
        // known-credential account exists.
        if (isDevProfile()) {
            List<Seed.Credential> credentials =
                    new Seed(users, patients, providers, claims, hasher).load();
            // Record a USER_CREATE audit entry per seeded account (PRD section 31).
            for (com.frauddetector.domain.User user : users.findAll()) {
                auditService.record("SYSTEM", AuditService.ACTION_USER_CREATE, user.getId(), null);
            }
            // Recompute provider risk scores now that seed claims exist.
            providerService.recomputeAll();
            Seed.printCredentials(credentials);
        } else {
            System.out.println("Non-dev profile (APP_ENV=" + System.getenv("APP_ENV")
                    + "): demo seeding disabled.");
        }
    }

    /** Dev-only token secret, used only when {@code APP_ENV=dev} and APP_SECRET is unset. */
    static final String DEV_SECRET = "fraud-detector-dev-secret-change-me";

    /**
     * Resolve the HMAC token secret. A real deployment MUST set {@code APP_SECRET};
     * if it is unset we only fall back to the committed dev secret when
     * {@code APP_ENV} is {@code dev} (or unset, which keeps the sandbox/demo
     * experience). In any other profile (e.g. {@code APP_ENV=prod}) a missing
     * {@code APP_SECRET} fails fast rather than silently minting forgeable tokens
     * from a public string (review finding #3).
     */
    private static String resolveSecret() {
        String env = System.getenv("APP_SECRET");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        if (isDevProfile()) {
            System.err.println("WARNING: APP_SECRET is not set; using the built-in dev secret. "
                    + "Set APP_SECRET before any real deployment.");
            return DEV_SECRET;
        }
        throw new IllegalStateException(
                "APP_SECRET must be set when APP_ENV is not 'dev' (refusing to start with a "
                        + "publicly-known token secret). Set APP_SECRET, or set APP_ENV=dev for local use.");
    }

    /** True when running under the dev profile: APP_ENV unset or equal to "dev". */
    private static boolean isDevProfile() {
        String profile = System.getenv("APP_ENV");
        return profile == null || profile.isBlank() || "dev".equalsIgnoreCase(profile.trim());
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
