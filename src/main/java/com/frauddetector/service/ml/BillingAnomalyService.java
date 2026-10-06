package com.frauddetector.service.ml;

import com.frauddetector.domain.Claim;
import com.frauddetector.repository.ClaimRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Billing-deviation analysis (PRD section 11). Compares the submitted amount for
 * a procedure (optionally scoped to the same provider) against the historical
 * "normal" range learned from prior claims, and reports:
 *
 * <ul>
 *   <li>an anomaly score in [0,1] (how abnormal the amount is), driven by the
 *       z-score against the historical mean/stddev;</li>
 *   <li>the percentage the amount sits above (or below) the historical average;</li>
 *   <li>the normal range used (mean, stddev, min, max, sample size).</li>
 * </ul>
 *
 * <p>With too few history points for a stable stddev, the detector falls back to
 * a min/max range check so a wildly out-of-range amount is still flagged.
 */
public final class BillingAnomalyService {

    private final ClaimRepository claims;

    public BillingAnomalyService(ClaimRepository claims) {
        this.claims = claims;
    }

    /** Result of a billing-anomaly comparison. */
    public static final class Result {
        public final double anomalyScore;        // [0,1]
        public final double percentAboveAverage; // e.g. 74.0 means 74% above mean
        public final double historicalMean;
        public final double historicalStdDev;
        public final double historicalMin;
        public final double historicalMax;
        public final int sampleSize;
        public final double zScore;

        Result(double anomalyScore, double percentAboveAverage, double historicalMean,
               double historicalStdDev, double historicalMin, double historicalMax,
               int sampleSize, double zScore) {
            this.anomalyScore = anomalyScore;
            this.percentAboveAverage = percentAboveAverage;
            this.historicalMean = historicalMean;
            this.historicalStdDev = historicalStdDev;
            this.historicalMin = historicalMin;
            this.historicalMax = historicalMax;
            this.sampleSize = sampleSize;
            this.zScore = zScore;
        }
    }

    /** Analyze a persisted claim (excluding itself from the historical population). */
    public Result analyze(Claim claim) {
        double amount = toDouble(claim.getTotalBilledAmount());
        List<Double> history = historicalAmounts(claim.getProcedure(), claim.getProviderId(), claim.getClaimId());
        return analyze(amount, history);
    }

    /**
     * Core computation against an explicit historical sample. Exposed for tests
     * so a known fixture (e.g. the PRD knee-surgery example) can be asserted.
     */
    public Result analyze(double amount, List<Double> history) {
        if (history.isEmpty()) {
            // No reference population: cannot judge; treat as neutral.
            return new Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0);
        }
        double mean = mean(history);
        double stddev = stdDev(history, mean);
        double min = history.get(0);
        double max = history.get(0);
        for (double v : history) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        double percentAboveAverage = mean == 0.0 ? 0.0 : ((amount - mean) / mean) * 100.0;

        double zScore;
        double anomalyScore;
        if (stddev > 1e-9) {
            zScore = (amount - mean) / stddev;
            // Map |z| through a saturating curve: z=2 (the flag threshold) -> 0.5,
            // z>=4 -> ~1. Only positive (above-average) deviations raise the score.
            anomalyScore = clamp01(Math.max(0.0, zScore) / 4.0);
        } else {
            // Degenerate stddev: fall back to min/max range membership.
            zScore = 0.0;
            if (amount > max) {
                double overshoot = max == 0.0 ? 1.0 : (amount - max) / max;
                anomalyScore = clamp01(overshoot);
            } else {
                anomalyScore = 0.0;
            }
        }
        return new Result(anomalyScore, percentAboveAverage, mean, stddev, min, max,
                history.size(), zScore);
    }

    private List<Double> historicalAmounts(String procedure, String providerId, String excludeClaimId) {
        // Prefer same-procedure history; if too thin, fall back to same provider.
        List<Double> byProcedure = new ArrayList<>();
        List<Double> byProvider = new ArrayList<>();
        for (Claim c : claims.findAll()) {
            if (c.getClaimId().equals(excludeClaimId)) {
                continue;
            }
            double amt = toDouble(c.getTotalBilledAmount());
            if (procedure != null && procedure.equalsIgnoreCase(c.getProcedure())) {
                byProcedure.add(amt);
            }
            if (providerId != null && providerId.equals(c.getProviderId())) {
                byProvider.add(amt);
            }
        }
        if (byProcedure.size() >= 2) {
            return byProcedure;
        }
        if (!byProcedure.isEmpty()) {
            return byProcedure;
        }
        return byProvider;
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double stdDev(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / values.size());
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    private static double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
