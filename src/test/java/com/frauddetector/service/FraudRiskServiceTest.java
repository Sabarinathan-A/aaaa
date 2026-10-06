package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Patient;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Proves the combined Fraud Risk Score (PRD sections 15-16): a clean baseline
 * claim consistent with history scores LOW, while a claim that is inflated,
 * duplicated, and from a high-risk provider scores HIGH or CRITICAL. Also checks
 * that classification respects the configurable thresholds. Run via
 * {@code ./build.sh test}.
 */
public final class FraudRiskServiceTest {

    public static void main(String[] args) {
        testCleanClaimIsLow();
        testInflatedDuplicateClaimIsHighOrCritical();
        testThresholdsAreConfigurable();
        System.out.println("FraudRiskServiceTest OK");
    }

    private static PatientRepository patients() {
        PatientRepository patients = new PatientRepository();
        patients.save(new Patient("PAT-1", "John", 45, "M", "Austin", "INS-1"));
        patients.save(new Patient("PAT-2", "Jane", 50, "F", "Austin", "INS-2"));
        return patients;
    }

    private static Claim knee(String id, String patient, String provider, LocalDate date, String total) {
        BigDecimal t = new BigDecimal(total);
        return new Claim(id, patient, provider, "HOSP-A", "Knee injury", "Knee Surgery",
                date, date.plusDays(3), date.plusDays(4),
                t.multiply(new BigDecimal("0.2")), t.multiply(new BigDecimal("0.3")),
                t.multiply(new BigDecimal("0.3")), t.multiply(new BigDecimal("0.1")),
                t.multiply(new BigDecimal("0.1")), t, t, "SUBMITTED", Instant.now());
    }

    /**
     * Seed a stable population of normal ~$50,000 knee surgeries from the
     * well-behaved provider PRV-2, establishing the procedure's normal range.
     */
    private static ClaimRepository populationOfNormalClaims() {
        ClaimRepository claims = new ClaimRepository();
        claims.save(knee("CLM-H1", "PAT-2", "PRV-2", LocalDate.of(2024, 1, 5), "50000"));
        claims.save(knee("CLM-H2", "PAT-2", "PRV-2", LocalDate.of(2024, 2, 5), "49000"));
        claims.save(knee("CLM-H3", "PAT-2", "PRV-2", LocalDate.of(2024, 3, 5), "51000"));
        claims.save(knee("CLM-H4", "PAT-2", "PRV-2", LocalDate.of(2024, 4, 5), "50500"));
        claims.save(knee("CLM-H5", "PAT-2", "PRV-2", LocalDate.of(2024, 5, 5), "50200"));
        return claims;
    }

    private static void testCleanClaimIsLow() {
        ClaimRepository claims = populationOfNormalClaims();
        FraudRiskService svc = new FraudRiskService(claims, patients(), ThresholdConfig.defaults());

        // A new claim right in line with the historical population + provider.
        Claim clean = knee("CLM-NEW", "PAT-1", "PRV-2", LocalDate.of(2024, 6, 1), "50000");
        claims.save(clean);
        FraudAnalysis a = svc.analyze(clean);

        Assert.assertTrue(a.getFinalRiskScore() <= 30.0,
                "a clean claim scores in the LOW band (score=" + a.getFinalRiskScore() + ")");
        Assert.assertEquals("LOW", a.getRiskLevel(), "clean claim is classified LOW");
    }

    private static void testInflatedDuplicateClaimIsHighOrCritical() {
        ClaimRepository claims = populationOfNormalClaims();
        PatientRepository patients = patients();
        FraudRiskService svc = new FraudRiskService(claims, patients, ThresholdConfig.defaults());

        // An existing claim from the high-risk provider PRV-1 to duplicate
        // (same patient/provider/date/amount), inflated to ~$120,000.
        Claim existing = knee("CLM-DUP", "PAT-1", "PRV-1", LocalDate.of(2024, 6, 10), "120000");
        claims.save(existing);

        // The submitted claim: wildly inflated AND a near-duplicate of CLM-DUP.
        Claim flagged = knee("CLM-BAD", "PAT-1", "PRV-1", LocalDate.of(2024, 6, 10), "120000");
        claims.save(flagged);
        FraudAnalysis a = svc.analyze(flagged);

        Assert.assertTrue(a.getFinalRiskScore() > 60.0,
                "an inflated + duplicate + high-provider-risk claim scores above 60 (score="
                        + a.getFinalRiskScore() + ")");
        Assert.assertTrue("HIGH".equals(a.getRiskLevel()) || "CRITICAL".equals(a.getRiskLevel()),
                "flagged claim is HIGH or CRITICAL (level=" + a.getRiskLevel() + ")");
        Assert.assertTrue(a.getBillingAnomalyScore() > 0.0, "billing anomaly is non-zero");
        Assert.assertTrue(a.getDuplicateScore() >= 0.85, "duplicate score is high");
    }

    private static void testThresholdsAreConfigurable() {
        // A config where everything above 10 is already CRITICAL.
        ThresholdConfig strict = new ThresholdConfig(5.0, 7.0, 9.0, 2.0, 0.85);
        Assert.assertEquals("LOW", strict.classify(3.0), "3 is LOW under strict config");
        Assert.assertEquals("MEDIUM", strict.classify(6.0), "6 is MEDIUM under strict config");
        Assert.assertEquals("HIGH", strict.classify(8.0), "8 is HIGH under strict config");
        Assert.assertEquals("CRITICAL", strict.classify(50.0), "50 is CRITICAL under strict config");

        ThresholdConfig d = ThresholdConfig.defaults();
        Assert.assertEquals("LOW", d.classify(30.0), "30 is the top of LOW");
        Assert.assertEquals("MEDIUM", d.classify(31.0), "31 enters MEDIUM");
        Assert.assertEquals("HIGH", d.classify(80.0), "80 is the top of HIGH");
        Assert.assertEquals("CRITICAL", d.classify(81.0), "81 enters CRITICAL");
    }
}
