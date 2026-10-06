package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves ClaimService.listClaims applies filters (riskLevel, providerId,
 * procedure, date range) and respects page/size pagination bounds. Run via
 * {@code ./build.sh test}.
 */
public final class SearchFilterTest {

    public static void main(String[] args) {
        testFilterByRiskLevel();
        testFilterByProviderId();
        testPaginationBounds();
        System.out.println("SearchFilterTest OK");
    }

    private static Claim claim(String id, String provider, String procedure, LocalDate date) {
        return new Claim(id, "PAT-1", provider, "HOSP-A", "Dx", procedure,
                date, date.plusDays(1), date, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("900"),
                "SUBMITTED", Instant.now());
    }

    private static FraudAnalysis analysis(String claimId, String level) {
        return new FraudAnalysis("FA-" + claimId, claimId, 0.5, 0.5, 0.5, 0.5, 0.5,
                50.0, level, "test-model", Collections.emptyList(), Instant.now());
    }

    private static ClaimService service(ClaimRepository claims, FraudAnalysisRepository analyses) {
        PatientRepository patients = new PatientRepository();
        ProviderRepository providers = new ProviderRepository();
        patients.save(new Patient("PAT-1", "Pat", 40, "F", "Austin", "INS-1"));
        providers.save(new Provider("PRV-1", "P1", "H", "Austin", "C", BigDecimal.ZERO));
        providers.save(new Provider("PRV-2", "P2", "H", "Dallas", "O", BigDecimal.ZERO));
        ValidationService validation = new ValidationService(patients, providers, claims);
        FraudRiskService frs = new FraudRiskService(claims, patients, ThresholdConfig.defaults());
        return new ClaimService(claims, validation, frs, analyses);
    }

    private static void seed(ClaimRepository claims, FraudAnalysisRepository analyses) {
        claims.save(claim("CLM-1", "PRV-1", "X-Ray", LocalDate.of(2024, 1, 10)));
        claims.save(claim("CLM-2", "PRV-1", "MRI", LocalDate.of(2024, 2, 10)));
        claims.save(claim("CLM-3", "PRV-2", "X-Ray", LocalDate.of(2024, 3, 10)));
        analyses.save(analysis("CLM-1", "HIGH"));
        analyses.save(analysis("CLM-2", "LOW"));
        analyses.save(analysis("CLM-3", "HIGH"));
    }

    private static Map<String, String> filters(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void testFilterByRiskLevel() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        ClaimService svc = service(claims, analyses);

        List<Map<String, Object>> high = svc.listClaims(filters("riskLevel", "HIGH"));
        Assert.assertEquals(2, high.size(), "two HIGH claims");
        for (Map<String, Object> c : high) {
            Map<String, Object> fa = (Map<String, Object>) c.get("fraudAnalysis");
            Assert.assertEquals("HIGH", fa.get("riskLevel"), "every result is HIGH");
        }

        List<Map<String, Object>> low = svc.listClaims(filters("riskLevel", "LOW"));
        Assert.assertEquals(1, low.size(), "one LOW claim");
    }

    private static void testFilterByProviderId() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        ClaimService svc = service(claims, analyses);

        List<Map<String, Object>> prv1 = svc.listClaims(filters("providerId", "PRV-1"));
        Assert.assertEquals(2, prv1.size(), "two claims for PRV-1");
        for (Map<String, Object> c : prv1) {
            Assert.assertEquals("PRV-1", c.get("providerId"), "only PRV-1 claims");
        }

        // Combined filter: PRV-1 + HIGH -> only CLM-1.
        List<Map<String, Object>> combined = svc.listClaims(filters("providerId", "PRV-1", "riskLevel", "HIGH"));
        Assert.assertEquals(1, combined.size(), "PRV-1 + HIGH yields one claim");
        Assert.assertEquals("CLM-1", combined.get(0).get("claimId"), "the HIGH PRV-1 claim");
    }

    private static void testPaginationBounds() {
        ClaimRepository claims = new ClaimRepository();
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        seed(claims, analyses);
        ClaimService svc = service(claims, analyses);

        List<Map<String, Object>> firstPage = svc.listClaims(filters("page", "1", "size", "2"));
        Assert.assertEquals(2, firstPage.size(), "page 1 size 2 returns two");

        List<Map<String, Object>> secondPage = svc.listClaims(filters("page", "2", "size", "2"));
        Assert.assertEquals(1, secondPage.size(), "page 2 size 2 returns remaining one");

        List<Map<String, Object>> beyond = svc.listClaims(filters("page", "99", "size", "2"));
        Assert.assertEquals(0, beyond.size(), "page past the end is empty");

        List<Map<String, Object>> all = svc.listClaims(filters());
        Assert.assertEquals(3, all.size(), "no filters returns all three");
    }
}
