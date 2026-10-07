package com.frauddetector.persistence;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Investigation;
import com.frauddetector.domain.Notification;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.domain.RiskFactor;
import com.frauddetector.domain.User;
import com.frauddetector.repository.AuditLogRepository;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.EvidenceRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.NotificationRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.AuditLog;
import com.frauddetector.security.Role;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Proves every entity survives a save / load round trip through the JSON
 * snapshot (the "no loss of claim information" requirement), including money
 * precision, dates, enums and nested risk factors. Run via {@code ./build.sh test}.
 */
public final class SnapshotStoreTest {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("snapshot-test");
        Path file = dir.resolve("snapshot.json");
        try {
            Repos a = new Repos();
            Instant t = Instant.parse("2025-06-01T10:15:30Z");
            a.users.save(new User("U-1", "Ann", "ann@x.io", "hash", "salt", Role.INVESTIGATOR, "ACTIVE", t));
            a.patients.save(new Patient("PAT-1", "Pat \"Quote\" O'Neil", 44, "F", "Austin", "INS-9"));
            a.providers.save(new Provider("PRV-1", "Prov", "Hosp", "Austin", "Cardio", new BigDecimal("0.1234")));
            a.claims.save(new Claim("CLM-1", "PAT-1", "PRV-1", "H", "Dx\nline2", "ECG", LocalDate.of(2025, 1, 2),
                    LocalDate.of(2025, 1, 3), LocalDate.of(2025, 1, 4), new BigDecimal("10.05"), BigDecimal.ZERO,
                    BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("11.05"), new BigDecimal("11"),
                    "SUSPICIOUS", t));
            a.analyses.save(new FraudAnalysis("FA-1", "CLM-1", 0.91, 0.8, 0.2, 0.3, 0.1, 77.7, "HIGH", "logreg-trained-v1",
                    List.of(new RiskFactor("Billing 74% above average", 0.25)), t));
            a.investigations.save(new Investigation("INV-1", "CLM-1", "U-1", "CLOSED", Investigation.Decision.REJECT,
                    "notes", t, t));
            a.notifications.save(new Notification("NOT-1", Role.INVESTIGATOR, "HIGH_RISK_CLAIM", "msg", "CLM-1", "HIGH", true, t));
            a.audit.save(new AuditLog("A-1", "U-1", "LOGIN", "U-1", t, "127.0.0.1"));
            a.store(file).save();

            Repos b = new Repos();
            Assert.assertTrue(b.store(file).load(), "snapshot loaded");
            Assert.assertEquals(Role.INVESTIGATOR, b.users.findById("U-1").orElseThrow().getRole(), "user role");
            Assert.assertEquals("Pat \"Quote\" O'Neil", b.patients.findById("PAT-1").orElseThrow().getName(), "escaped name");
            Assert.assertEquals(new BigDecimal("0.1234"), b.providers.findById("PRV-1").orElseThrow().getRiskScore(), "provider score");
            Claim c = b.claims.findById("CLM-1").orElseThrow();
            Assert.assertEquals(new BigDecimal("10.05"), c.getMedicineCost(), "money precision");
            Assert.assertEquals(LocalDate.of(2025, 1, 3), c.getDischargeDate(), "date");
            Assert.assertEquals("Dx\nline2", c.getDiagnosis(), "newline preserved");
            Assert.assertEquals("SUSPICIOUS", c.getClaimStatus(), "status");
            Assert.assertEquals(t, c.getCreatedAt(), "instant");
            FraudAnalysis fa = b.analyses.findByClaimId("CLM-1").orElseThrow();
            Assert.assertEquals(77.7, fa.getFinalRiskScore(), "score");
            Assert.assertEquals("Billing 74% above average", fa.getRiskFactors().get(0).getDescription(), "risk factor");
            Assert.assertEquals(Investigation.Decision.REJECT, b.investigations.findById("INV-1").orElseThrow().getDecision(), "decision");
            Assert.assertTrue(b.notifications.findById("NOT-1").orElseThrow().isRead(), "read flag");
            Assert.assertEquals("127.0.0.1", b.audit.findById("A-1").orElseThrow().getIp(), "audit ip");

            // Second save keeps the previous file as a backup.
            a.store(file).save();
            Assert.assertTrue(Files.exists(dir.resolve("snapshot.json.bak")), "backup kept");
            Assert.assertFalse(new Repos().store(dir.resolve("missing.json")).load(), "missing file -> false");
        } finally {
            try (var files = Files.list(dir)) {
                files.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
        System.out.println("SnapshotStoreTest OK");
    }

    private static final class Repos {
        final UserRepository users = new UserRepository();
        final PatientRepository patients = new PatientRepository();
        final ProviderRepository providers = new ProviderRepository();
        final ClaimRepository claims = new ClaimRepository();
        final FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        final InvestigationRepository investigations = new InvestigationRepository();
        final NotificationRepository notifications = new NotificationRepository();
        final AuditLogRepository audit = new AuditLogRepository();
        final EvidenceRepository evidence = new EvidenceRepository();

        SnapshotStore store(Path file) {
            return new SnapshotStore(file, users, patients, providers, claims, analyses, investigations,
                    notifications, audit, evidence);
        }
    }
}
