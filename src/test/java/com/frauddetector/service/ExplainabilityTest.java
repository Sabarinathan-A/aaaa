package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.RiskFactor;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Proves Explainable-AI risk factors (PRD section 17) are generated from the
 * actual anomalies: a flagged claim yields a non-empty, ordered list of
 * human-readable factors that reference the real deviations (e.g. the billing
 * percentage above average), while the factors are sorted by contribution. Run
 * via {@code ./build.sh test}.
 */
public final class ExplainabilityTest {

    public static void main(String[] args) {
        testFlaggedClaimHasReferencedRiskFactors();
        System.out.println("ExplainabilityTest OK");
    }

    private static Patient patient() {
        return new Patient("PAT-1", "John", 45, "M", "Austin", "INS-1");
    }

    private static Claim knee(String id, String provider, LocalDate date, String total) {
        BigDecimal t = new BigDecimal(total);
        return new Claim(id, "PAT-1", provider, "HOSP-A", "Knee injury", "Knee Surgery",
                date, date.plusDays(3), date.plusDays(4),
                t.multiply(new BigDecimal("0.2")), t.multiply(new BigDecimal("0.3")),
                t.multiply(new BigDecimal("0.3")), t.multiply(new BigDecimal("0.1")),
                t.multiply(new BigDecimal("0.1")), t, t, "SUBMITTED", Instant.now());
    }

    private static void testFlaggedClaimHasReferencedRiskFactors() {
        ClaimRepository claims = new ClaimRepository();
        PatientRepository patients = new PatientRepository();
        patients.save(patient());

        // Normal history ~$50,000.
        claims.save(knee("CLM-H1", "PRV-1", LocalDate.of(2024, 1, 5), "50000"));
        claims.save(knee("CLM-H2", "PRV-1", LocalDate.of(2024, 2, 5), "49000"));
        claims.save(knee("CLM-H3", "PRV-1", LocalDate.of(2024, 3, 5), "51000"));
        // A claim to duplicate.
        claims.save(knee("CLM-DUP", "PRV-1", LocalDate.of(2024, 6, 10), "120000"));

        FraudRiskService svc = new FraudRiskService(claims, patients, ThresholdConfig.defaults());

        // Inflated + duplicate claim.
        Claim flagged = knee("CLM-BAD", "PRV-1", LocalDate.of(2024, 6, 10), "120000");
        claims.save(flagged);
        FraudAnalysis a = svc.analyze(flagged);

        List<RiskFactor> factors = a.getRiskFactors();
        Assert.assertFalse(factors.isEmpty(), "a flagged claim produces at least one risk factor");

        // Factors reference actual anomalies (billing % and/or a duplicate match).
        boolean mentionsBilling = false;
        boolean mentionsDuplicate = false;
        double previous = Double.MAX_VALUE;
        for (RiskFactor f : factors) {
            Assert.assertNotNull(f.getDescription(), "risk factor has a description");
            Assert.assertFalse(f.getDescription().isBlank(), "risk factor description is non-empty");
            // Ordered by descending contribution.
            Assert.assertTrue(f.getContribution() <= previous + 1e-9,
                    "risk factors are ordered by descending contribution");
            previous = f.getContribution();

            String d = f.getDescription().toLowerCase();
            if (d.contains("above the historical average")) {
                mentionsBilling = true;
            }
            if (d.contains("similar claim detected")) {
                mentionsDuplicate = true;
            }
        }
        Assert.assertTrue(mentionsBilling || mentionsDuplicate,
                "risk factors reference the real anomalies (inflated billing and/or duplicate)");
    }
}
