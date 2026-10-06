package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.dto.ClaimResponse;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Regression test for review findings #1, #2 and #6: repeat-inflation must not
 * dilute its own signal.
 *
 * <p>Before the clean-baseline fix, the billing "normal" range was drawn from
 * the live, mutable claim population, so a cluster of identical inflated claims
 * saw the earlier copies as "normal". The second and later copies therefore
 * scored LOWER than the first (observed HIGH/MEDIUM/MEDIUM for three identical
 * $100k claims), and potentialSavings read ~0 exactly when repeat-inflation fraud
 * was present.
 *
 * <p>This test submits three identical inflated claims in sequence through the
 * full {@link ClaimService} pipeline (so each is scored and its analysis stored,
 * exactly as production does) against a clean seed baseline, and asserts:
 * <ul>
 *   <li>the later duplicates do NOT score below the first copy;</li>
 *   <li>all three remain flagged HIGH/CRITICAL (none decays to MEDIUM/LOW);</li>
 *   <li>potentialSavings stays strictly positive.</li>
 * </ul>
 * Run via {@code ./build.sh test}.
 */
public final class RepeatInflationTest {

    private static final Principal OFFICER = new Principal("U-OFFICER", Role.CLAIM_OFFICER);

    public static void main(String[] args) {
        testRepeatInflatedClaimsDoNotDecay();
        System.out.println("RepeatInflationTest OK");
    }

    private static void testRepeatInflatedClaimsDoNotDecay() {
        ClaimRepository claims = new ClaimRepository();
        PatientRepository patients = new PatientRepository();
        ProviderRepository providers = new ProviderRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();

        patients.save(new Patient("PAT-1", "John", 45, "M", "Austin", "INS-1"));
        providers.save(new Provider("PRV-1", "Prov", "Hosp", "Austin", "Ortho", BigDecimal.ZERO));

        // Clean baseline: a stable population of legitimate ~$10,000 knee
        // surgeries, so an inflated $100,000 claim is a clear outlier.
        claims.save(knee("CLM-H1", LocalDate.of(2024, 1, 5), "10000"));
        claims.save(knee("CLM-H2", LocalDate.of(2024, 2, 5), "9800"));
        claims.save(knee("CLM-H3", LocalDate.of(2024, 3, 5), "10200"));
        claims.save(knee("CLM-H4", LocalDate.of(2024, 4, 5), "10100"));
        claims.save(knee("CLM-H5", LocalDate.of(2024, 5, 5), "9900"));

        ValidationService validation = new ValidationService(patients, providers, claims);
        // Full wiring (analyses repo) so the billing baseline excludes already
        // flagged claims as well as near-duplicates.
        FraudRiskService fraudRiskService =
                new FraudRiskService(claims, patients, analyses, ThresholdConfig.defaults());
        ClaimService claimService = new ClaimService(claims, validation, fraudRiskService, analyses);
        DashboardService dashboard =
                new DashboardService(claims, analyses, providers, ThresholdConfig.defaults());

        // Submit three identical inflated $100,000 knee-surgery claims in sequence.
        ClaimResponse first = claimService.submitClaim(inflated("CLM-BAD-1"), OFFICER);
        ClaimResponse second = claimService.submitClaim(inflated("CLM-BAD-2"), OFFICER);
        ClaimResponse third = claimService.submitClaim(inflated("CLM-BAD-3"), OFFICER);

        double s1 = score(first);
        double s2 = score(second);
        double s3 = score(third);

        // The core regression: later identical inflated claims must not score
        // below the first copy. (Before the fix: s2, s3 < s1.)
        Assert.assertTrue(s2 >= s1 - 0.001,
                "second identical inflated claim does not score below the first (s1=" + s1
                        + ", s2=" + s2 + ")");
        Assert.assertTrue(s3 >= s1 - 0.001,
                "third identical inflated claim does not score below the first (s1=" + s1
                        + ", s3=" + s3 + ")");

        // All three remain flagged (none decays out of the HIGH/CRITICAL set).
        ThresholdConfig t = ThresholdConfig.defaults();
        Assert.assertTrue(t.isHighRisk(level(first)), "first inflated claim is HIGH/CRITICAL");
        Assert.assertTrue(t.isHighRisk(level(second)),
                "second inflated claim stays HIGH/CRITICAL (level=" + level(second) + ")");
        Assert.assertTrue(t.isHighRisk(level(third)),
                "third inflated claim stays HIGH/CRITICAL (level=" + level(third) + ")");

        // The billing anomaly signal must survive on the repeats, not collapse.
        Assert.assertTrue(billing(second) > 0.0,
                "second claim still shows a non-zero billing anomaly (" + billing(second) + ")");
        Assert.assertTrue(billing(third) > 0.0,
                "third claim still shows a non-zero billing anomaly (" + billing(third) + ")");

        // potentialSavings must stay strictly positive under repeat inflation.
        BigDecimal savings = dashboard.potentialSavings();
        Assert.assertTrue(savings.signum() > 0,
                "potentialSavings is positive under repeat inflation (" + savings.toPlainString() + ")");
    }

    private static SubmitClaimRequest inflated(String claimId) {
        return new SubmitClaimRequest(
                claimId, "PAT-1", "PRV-1", "HOSP-A", "Knee injury", "Knee Surgery",
                LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 4), LocalDate.of(2024, 6, 5),
                new BigDecimal("20000"), new BigDecimal("30000"), new BigDecimal("30000"),
                new BigDecimal("10000"), new BigDecimal("10000"),
                new BigDecimal("100000"), new BigDecimal("90000"));
    }

    private static Claim knee(String id, LocalDate date, String total) {
        BigDecimal t = new BigDecimal(total);
        return new Claim(id, "PAT-1", "PRV-1", "HOSP-A", "Knee injury", "Knee Surgery",
                date, date.plusDays(3), date.plusDays(4),
                t.multiply(new BigDecimal("0.2")), t.multiply(new BigDecimal("0.3")),
                t.multiply(new BigDecimal("0.3")), t.multiply(new BigDecimal("0.1")),
                t.multiply(new BigDecimal("0.1")), t, t, "SUBMITTED", Instant.now());
    }

    private static double score(ClaimResponse r) {
        return ((Number) field(r, "finalRiskScore")).doubleValue();
    }

    private static double billing(ClaimResponse r) {
        return ((Number) field(r, "billingAnomalyScore")).doubleValue();
    }

    private static String level(ClaimResponse r) {
        return String.valueOf(field(r, "riskLevel"));
    }

    @SuppressWarnings("unchecked")
    private static Object field(ClaimResponse r, String key) {
        Map<String, Object> json = r.toJson();
        Object analysis = json.get("fraudAnalysis");
        if (analysis instanceof Map) {
            return ((Map<String, Object>) analysis).get(key);
        }
        return json.get(key);
    }
}
