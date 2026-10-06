package com.frauddetector.service.ml;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;

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
 *
 * <p><b>Clean baseline (review findings #1/#2).</b> The historical "normal" range
 * must not be contaminated by the fraudulent claims it is meant to catch,
 * otherwise a cluster of identical inflated claims erodes its own signal: the
 * second and later copies see the first copies as "normal", so their billing
 * anomaly (and the derived savings/inflation KPIs) collapse toward zero. When
 * this service is given a {@link FraudAnalysisRepository}, a {@link ThresholdConfig}
 * and a {@link DuplicateDetector}, it excludes from the baseline any claim that is
 * already flagged HIGH/CRITICAL or is a near-duplicate of the claim under
 * analysis. The baseline therefore reflects the clean/legitimate population only,
 * and repeat inflation can no longer dilute its own detection.
 */
public final class BillingAnomalyService {

    private final ClaimRepository claims;
    private final FraudAnalysisRepository analyses;
    private final ThresholdConfig thresholds;
    private final DuplicateDetector duplicateDetector;

    /**
     * Minimal constructor used by unit tests and callers that only need the core
     * {@link #analyze(double, List)} computation over an explicit history. With no
     * analysis repository or duplicate detector, no baseline filtering is applied.
     */
    public BillingAnomalyService(ClaimRepository claims) {
        this(claims, null, null, null);
    }

    /**
     * Full constructor with clean-baseline filtering. Already-flagged
     * (HIGH/CRITICAL) claims and near-duplicates of the claim under analysis are
     * excluded from the historical "normal" population so repeat inflation does
     * not erase its own signal.
     */
    public BillingAnomalyService(ClaimRepository claims, FraudAnalysisRepository analyses,
                                 ThresholdConfig thresholds, DuplicateDetector duplicateDetector) {
        this.claims = claims;
        this.analyses = analyses;
        this.thresholds = thresholds;
        this.duplicateDetector = duplicateDetector;
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

    /**
     * Analyze a persisted claim against the historical "normal" population,
     * excluding the claim itself and (when the clean-baseline collaborators are
     * wired) any already-flagged or near-duplicate claim so repeat inflation
     * cannot dilute its own signal.
     */
    public Result analyze(Claim claim) {
        double amount = toDouble(claim.getTotalBilledAmount());
        List<Double> history = historicalAmounts(claim);
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

    private List<Double> historicalAmounts(Claim target) {
        String procedure = target.getProcedure();
        String providerId = target.getProviderId();
        String excludeClaimId = target.getClaimId();
        // Prefer same-procedure history; if too thin, fall back to same provider.
        List<Double> byProcedure = new ArrayList<>();
        List<Double> byProvider = new ArrayList<>();
        for (Claim c : claims.findAll()) {
            if (c.getClaimId().equals(excludeClaimId)) {
                continue;
            }
            // Keep the "normal" baseline clean: a claim that is itself already
            // flagged as fraud, or a near-duplicate of the claim under analysis,
            // must not define what "normal" billing looks like. Excluding them
            // stops repeat inflation from eroding its own signal (review #1/#2).
            if (isContaminating(target, c)) {
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

    /**
     * A historical claim is "contaminating" (and so excluded from the normal
     * baseline) if it is already flagged HIGH/CRITICAL, or if it is a
     * near-duplicate of the claim under analysis. No-ops when the optional
     * clean-baseline collaborators are not wired.
     */
    private boolean isContaminating(Claim target, Claim candidate) {
        if (analyses != null && thresholds != null) {
            FraudAnalysis a = analyses.findByClaimId(candidate.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                return true;
            }
        }
        if (duplicateDetector != null && thresholds != null) {
            double sim = duplicateDetector.similarity(target, candidate);
            if (sim >= thresholds.getDuplicateThreshold()) {
                return true;
            }
        }
        return false;
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
