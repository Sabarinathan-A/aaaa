package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Provider;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Proves KPI cards are computed from a seeded set: totalClaims, flaggedClaims,
 * highRiskClaims counts and potentialSavings (billing excess over the historical
 * normal). Also checks the chart datasets are present and consistent. Run via
 * {@code ./build.sh test}.
 */
public final class DashboardServiceTest {

    public static void main(String[] args) {
        testKpis();
        testCharts();
        System.out.println("DashboardServiceTest OK");
    }

    private static Claim claim(String id, String provider, String procedure, String amount, LocalDate date) {
        return new Claim(id, "PAT-1", provider, "HOSP-A", "Dx", procedure,
                date, date.plusDays(1), date, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal(amount), new BigDecimal(amount),
                "SUBMITTED", Instant.now());
    }

    private static FraudAnalysis analysis(String claimId, String level) {
        return new FraudAnalysis("FA-" + claimId, claimId, 0.5, 0.5, 0.5, 0.5, 0.5,
                50.0, level, "test-model", Collections.emptyList(), Instant.now());
    }

    private static DashboardService build(ClaimRepository claims, FraudAnalysisRepository analyses) {
        ProviderRepository providers = new ProviderRepository();
        providers.save(new Provider("PRV-1", "Prov One", "Hosp", "Austin", "Cardio", new BigDecimal("0.30")));
        providers.save(new Provider("PRV-2", "Prov Two", "Hosp", "Dallas", "Ortho", new BigDecimal("0.10")));
        return new DashboardService(claims, analyses, providers, ThresholdConfig.defaults());
    }

    private static void seed(ClaimRepository claims, FraudAnalysisRepository analyses) {
        // Three "X-Ray" claims: two normal at 1000, one flagged (HIGH) at 3000.
        claims.save(claim("CLM-1", "PRV-1", "X-Ray", "1000", LocalDate.of(2024, 1, 10)));
        claims.save(claim("CLM-2", "PRV-1", "X-Ray", "1000", LocalDate.of(2024, 2, 10)));
        Claim flagged = claim("CLM-3", "PRV-1", "X-Ray", "3000", LocalDate.of(2024, 3, 10));
        claims.save(flagged);
        // A fourth, LOW claim with a distinct procedure.
        claims.save(claim("CLM-4", "PRV-2", "Consult", "500", LocalDate.of(2024, 3, 15)));

        analyses.save(analysis("CLM-1", "LOW"));
        analyses.save(analysis("CLM-2", "MEDIUM"));
        analyses.save(analysis("CLM-3", "HIGH"));
        analyses.save(analysis("CLM-4", "LOW"));
    }

    @SuppressWarnings("unchecked")
    private static void testKpis() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        DashboardService svc = build(claims, analyses);

        Map<String, Object> kpis = (Map<String, Object>) svc.build().get("kpis");
        Assert.assertEquals(4, kpis.get("totalClaims"), "totalClaims counts every claim");
        Assert.assertEquals(1, kpis.get("flaggedClaims"), "only the HIGH claim is flagged");
        Assert.assertEquals(1, kpis.get("highRiskClaims"), "highRiskClaims equals flagged");

        // Flagged claim CLM-3 is 3000; historical mean of the other X-Ray claims
        // (excluding itself) is 1000 -> excess = 2000.00.
        Assert.assertEquals("2000.00", kpis.get("potentialSavings"), "potentialSavings = billing excess");
    }

    @SuppressWarnings("unchecked")
    private static void testCharts() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        DashboardService svc = build(claims, analyses);

        Map<String, Object> charts = (Map<String, Object>) svc.build().get("charts");
        Assert.assertNotNull(charts.get("claimsOverTimeByDay"), "claims-over-time dataset present");
        Assert.assertNotNull(charts.get("providerRiskRanking"), "provider-risk dataset present");

        List<Map<String, Object>> fvl = (List<Map<String, Object>>) charts.get("fraudVsLegitimate");
        Assert.assertNotNull(fvl, "fraud-vs-legitimate dataset present");
        Assert.assertEquals(2, fvl.size(), "two fraud-vs-legitimate buckets");
        Assert.assertEquals("Suspicious", fvl.get(0).get("label"), "first bucket is Suspicious");
        Assert.assertEquals(1, fvl.get(0).get("count"), "one suspicious claim");
        Assert.assertEquals(3, fvl.get(1).get("count"), "three legitimate claims");

        // Provider ranking: PRV-1 (riskScore 0.30) ranks ahead of PRV-2 (0.10).
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) charts.get("providerRiskRanking");
        Assert.assertEquals("PRV-1", ranking.get(0).get("providerId"), "highest-risk provider first");
    }
}
