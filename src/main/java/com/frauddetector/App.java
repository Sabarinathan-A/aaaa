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
import com.frauddetector.controller.WebController;
import com.frauddetector.controller.MlController;
import com.frauddetector.controller.PatientController;
import com.frauddetector.controller.UserController;
import com.frauddetector.notify.EmailNotifier;
import com.frauddetector.notify.WebhookNotifier;
import com.frauddetector.persistence.SnapshotStore;
import com.frauddetector.repository.EvidenceRepository;
import com.frauddetector.service.EvidenceService;
import com.frauddetector.service.MlService;
import com.frauddetector.service.PatientService;
import com.frauddetector.service.UserService;
import java.nio.file.Path;
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

        String keystore = System.getenv("HTTPS_KEYSTORE");
        HttpServer server;
        String scheme;
        if (keystore != null && !keystore.isBlank()) {
            server = createHttpsServer(port, keystore.trim(), System.getenv("HTTPS_KEYSTORE_PASSWORD"));
            scheme = "https";
        } else {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            scheme = "http";
        }
        server.createContext("/", router);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("Fraud Detector listening on " + scheme + "://localhost:" + port);
    }

    /**
     * HTTPS (PRD section 30) using a PKCS12 keystore, e.g. one created with
     * {@code keytool -genkeypair -alias fraud -keyalg RSA -keysize 2048 -validity 365
     * -storetype PKCS12 -keystore data/keystore.p12 -dname CN=localhost}.
     * Only TLS 1.2 and 1.3 are offered.
     */
    private static HttpServer createHttpsServer(int port, String keystorePath, String password) throws IOException {
        if (password == null) {
            throw new IllegalStateException("HTTPS_KEYSTORE_PASSWORD must be set when HTTPS_KEYSTORE is set");
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(keystorePath))) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(in, password.toCharArray());
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password.toCharArray());
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            com.sun.net.httpserver.HttpsServer https =
                    com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(port), 0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl) {
                @Override
                public void configure(com.sun.net.httpserver.HttpsParameters params) {
                    javax.net.ssl.SSLParameters p = ssl.getDefaultSSLParameters();
                    p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    params.setSSLParameters(p);
                }
            });
            return https;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not load HTTPS keystore: " + e.getMessage(), e);
        }
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
        EvidenceRepository evidenceRepo = new EvidenceRepository();

        // Durable storage: restore the last snapshot (if any) before anything else.
        Path dataDir = Path.of(envOr("DATA_DIR", "data"));
        boolean persistence = !"off".equalsIgnoreCase(envOr("PERSISTENCE", "on"));
        SnapshotStore snapshot = new SnapshotStore(dataDir.resolve("snapshot.json"), users, patients,
                providers, claims, analyses, investigations, notifications, auditLogs, evidenceRepo);
        boolean restored = false;
        if (persistence) {
            try {
                restored = snapshot.load();
            } catch (java.io.IOException | RuntimeException e) {
                // Never overwrite a snapshot we could not read: fail fast instead.
                throw new IllegalStateException("Could not read " + snapshot.file().toAbsolutePath()
                        + " (" + e.getMessage() + "). Fix or move the file (a .bak copy may exist).", e);
            }
            if (restored) {
                System.out.println("Restored " + claims.count() + " claim(s), " + users.count()
                        + " user(s) from " + snapshot.file().toAbsolutePath());
            }
        }

        // Security.
        PasswordHasher hasher = new PasswordHasher();
        TokenService tokenService = new TokenService(resolveSecret(), resolveTokenTtl());
        AuthFilter authFilter = new AuthFilter(tokenService);
        UserService userService = new UserService(users, hasher);
        authFilter.setActiveUserCheck(userService::isActive);

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
        PatientService patientService = new PatientService(patients, claims, analyses);
        EvidenceService evidenceService =
                new EvidenceService(evidenceRepo, investigations, dataDir.resolve("evidence"));
        MlService mlService = new MlService(claims, patients, investigations);

        // External notification channels (in-app records are always created).
        notificationService.addExternalNotifier(new EmailNotifier(users, dataDir.resolve("outbox"),
                EmailNotifier.SmtpConfig.fromEnv()));
        String webhook = System.getenv("NOTIFY_WEBHOOK_URL");
        if (webhook != null && !webhook.isBlank()) {
            notificationService.addExternalNotifier(new WebhookNotifier(webhook.trim()));
        }

        // Controllers. InvestigationController + ReportController register literal
        // routes (/api/claims/high-risk, /api/reports/fraud-analytics) that must
        // come before the templated controllers so the literal match wins.
        new AuthController(authService, auditService).register(router);
        new InvestigationController(investigationService, claimService, authFilter, auditService)
                .withEvidence(evidenceService)
                .register(router);
        new ClaimController(claimService, authFilter, auditService).register(router);
        new DashboardController(dashboardService, authFilter).register(router);
        new AuditController(auditService, authFilter).register(router);
        new ReportController(reportService, authFilter).register(router);
        new NotificationController(notificationService, authFilter).register(router);
        new ProviderController(providerService, authFilter, auditService).register(router);
        new PatientController(patientService, authFilter, auditService).register(router);
        new UserController(userService, authFilter, auditService).register(router);
        new MlController(mlService, claimService, authFilter, auditService).register(router);
        new WebController().register(router);

        // Seed demo data only under the dev profile. The seed creates four
        // fixed accounts with known passwords printed to stdout, which is fine
        // for the sandbox demo but must never run in a real deployment (review
        // finding #4). In a non-dev profile, start with empty stores so no
        // known-credential account exists.
        if (restored) {
            providerService.recomputeAll();
            System.out.println("Using existing data; demo seeding skipped.");
        } else if (isDevProfile()) {
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
        bootstrapAdmin(userService, users);

        // Train the fraud model + isolation forest (synthetic labels + investigator
        // feedback), then score any claims that have no analysis yet.
        mlService.train();
        fraudRiskService.setMlService(mlService);
        boolean unscored = claims.findAll().stream()
                .anyMatch(c -> analyses.findByClaimId(c.getClaimId()).isEmpty());
        if (unscored) {
            claimService.rescoreAll();
            providerService.recomputeAll();
        }
        System.out.println("Fraud model " + mlService.model().version() + " trained: "
                + mlService.model().metrics().toJson());

        if (persistence) {
            snapshot.saveIfDirty();
            snapshot.startAutoSave(2);
            System.out.println("Persistence: " + snapshot.file().toAbsolutePath() + " (autosave every 2s)");
        } else {
            System.out.println("Persistence: OFF (PERSISTENCE=off) - data is lost on restart");
        }
    }

    /**
     * In a non-dev profile with an empty user store, create the first ADMIN from
     * {@code BOOTSTRAP_ADMIN_EMAIL} / {@code BOOTSTRAP_ADMIN_PASSWORD} so the
     * system is administrable without the demo seed.
     */
    private static void bootstrapAdmin(UserService userService, UserRepository users) {
        if (users.count() > 0) {
            return;
        }
        String email = System.getenv("BOOTSTRAP_ADMIN_EMAIL");
        String password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");
        if (email == null || password == null) {
            System.out.println("No users exist. Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD to create the first admin.");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Administrator");
        body.put("email", email);
        body.put("password", password);
        body.put("role", "ADMIN");
        userService.create(body);
        System.out.println("Created bootstrap admin " + email);
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
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
