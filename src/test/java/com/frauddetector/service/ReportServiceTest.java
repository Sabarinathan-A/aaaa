package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Provider;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

/**
 * Proves the provider report fraud rate = flaggedClaims/totalClaims and that the
 * fraud-analytics totals/suspicious counts are consistent with the seeded set.
 * Run via {@code ./build.sh test}.
 */
public final class ReportServiceTest {

    public static void main(String[] args) {
        testProviderReportFraudRate();
        testFraudAnalyticsTotals();
        System.out.println("ReportServiceTest OK");
    }

    private static Claim claim(String id, String provider, String procedure, String amount) {
        return new Claim(id, "PAT-1", provider, "HOSP-A", "Dx", procedure,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount), "SUBMITTED", Instant.now());
    }

    private static FraudAnalysis analysis(String claimId, String level) {
        return new FraudAnalysis("FA-" + claimId, claimId, 0.5, 0.5, 0.5, 0.5, 0.5,
                50.0, level, "test-model", Collections.emptyList(), Instant.now());
    }

    private static ReportService build(ClaimRepository claims, FraudAnalysisRepository analyses) {
        ProviderRepository providers = new ProviderRepository();
        providers.save(new Provider("PRV-1", "Prov One", "Hosp", "Austin", "Cardio", new BigDecimal("0.40")));
        providers.save(new Provider("PRV-2", "Prov Two", "Hosp", "Dallas", "Ortho", new BigDecimal("0.10")));
        return new ReportService(claims, analyses, providers, new InvestigationRepository(),
                ThresholdConfig.defaults());
    }

    private static void seed(ClaimRepository claims, FraudAnalysisRepository analyses) {
        // PRV-1: 4 claims, 3 flagged (HIGH/CRITICAL) -> fraud rate 0.75.
        claims.save(claim("CLM-1", "PRV-1", "X-Ray", "1000"));
        claims.save(claim("CLM-2", "PRV-1", "X-Ray", "4000"));
        claims.save(claim("CLM-3", "PRV-1", "X-Ray", "5000"));
        claims.save(claim("CLM-4", "PRV-1", "X-Ray", "900"));
        // PRV-2: 1 claim, 0 flagged.
        claims.save(claim("CLM-5", "PRV-2", "Consult", "300"));

        analyses.save(analysis("CLM-1", "HIGH"));
        analyses.save(analysis("CLM-2", "CRITICAL"));
        analyses.save(analysis("CLM-3", "HIGH"));
        analyses.save(analysis("CLM-4", "LOW"));
        analyses.save(analysis("CLM-5", "LOW"));
    }

    private static void testProviderReportFraudRate() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        ReportService svc = build(claims, analyses);

        Map<String, Object> report = svc.providerReport("PRV-1");
        Assert.assertEquals(4, report.get("totalClaims"), "PRV-1 has four claims");
        Assert.assertEquals(3, report.get("flaggedClaims"), "three flagged");
        // fraudRate = flagged/total = 3/4 = 0.75
        Assert.assertEquals(0.75, report.get("fraudRate"), "fraud rate = flagged/total");

        Map<String, Object> clean = svc.providerReport("PRV-2");
        Assert.assertEquals(1, clean.get("totalClaims"), "PRV-2 has one claim");
        Assert.assertEquals(0, clean.get("flaggedClaims"), "none flagged");
        Assert.assertEquals(0.0, clean.get("fraudRate"), "zero fraud rate");
    }

    private static void testFraudAnalyticsTotals() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        ReportService svc = build(claims, analyses);

        Map<String, Object> analytics = svc.fraudAnalytics();
        Assert.assertEquals(5, analytics.get("totalClaims"), "five total claims");
        Assert.assertEquals(3, analytics.get("suspiciousClaims"), "three suspicious claims");
        // fraudRate = 3/5 = 0.6
        Assert.assertEquals(0.6, analytics.get("fraudRate"), "portfolio fraud rate 3/5");
        Assert.assertNotNull(analytics.get("topRiskProviders"), "top risk providers present");
        Assert.assertNotNull(analytics.get("topRiskTreatments"), "top risk treatments present");
        Assert.assertNotNull(analytics.get("monthlyTrends"), "monthly trends present");
    }
}
