package com.frauddetector.service.ml;

import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.service.ml.BillingAnomalyService.Result;
import com.frauddetector.testkit.Assert;

import java.util.List;

/**
 * Proves the billing-deviation analyzer (PRD section 11) flags an amount far
 * above the historical range as a HIGH billing anomaly and reports the correct
 * percentage above the historical average, using the PRD knee-surgery-style
 * fixture (normal knee surgeries ~$50,000; a $87,000 claim is 74% above the
 * $50,000 average). Run via {@code ./build.sh test}.
 */
public final class BillingAnomalyServiceTest {

    public static void main(String[] args) {
        testInflatedAmountIsHighAnomaly();
        testNormalAmountIsLowAnomaly();
        System.out.println("BillingAnomalyServiceTest OK");
    }

    private static void testInflatedAmountIsHighAnomaly() {
        BillingAnomalyService svc = new BillingAnomalyService(new ClaimRepository());
        // Historical knee-surgery claims clustered around $50,000.
        List<Double> history = List.of(48000.0, 50000.0, 52000.0, 49000.0, 51000.0);
        // Submitted claim: $87,000 -> 74% above the $50,000 mean.
        Result r = svc.analyze(87000.0, history);

        Assert.assertEquals(50000.0, r.historicalMean, "historical mean is $50,000");
        // 74% above average: (87000 - 50000) / 50000 * 100 = 74.
        long pct = Math.round(r.percentAboveAverage);
        Assert.assertEquals(74L, pct, "percentage above average is 74%");
        Assert.assertTrue(r.anomalyScore >= 0.7,
                "an amount far above range yields a HIGH billing anomaly (score=" + r.anomalyScore + ")");
        Assert.assertTrue(r.zScore > 2.0, "z-score is well beyond the 2-sigma threshold");
        Assert.assertEquals(5, r.sampleSize, "normal range used all 5 history points");
    }

    private static void testNormalAmountIsLowAnomaly() {
        BillingAnomalyService svc = new BillingAnomalyService(new ClaimRepository());
        List<Double> history = List.of(48000.0, 50000.0, 52000.0, 49000.0, 51000.0);
        // A claim right at the historical average should not be anomalous.
        Result r = svc.analyze(50000.0, history);
        Assert.assertTrue(r.anomalyScore < 0.3,
                "an in-range amount yields a LOW billing anomaly (score=" + r.anomalyScore + ")");
        Assert.assertEquals(0L, Math.round(r.percentAboveAverage), "a mean amount is 0% above average");
    }
}
