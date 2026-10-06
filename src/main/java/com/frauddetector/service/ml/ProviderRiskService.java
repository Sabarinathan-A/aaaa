package com.frauddetector.service.ml;

import com.frauddetector.domain.Claim;
import com.frauddetector.repository.ClaimRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provider-level behavior analysis (PRD section 13). Derives a provider risk
 * score in [0,1] from:
 *
 * <ul>
 *   <li>how far the provider's average claim amount sits above the population
 *       average (inflated billing),</li>
 *   <li>claim volume relative to the population (unusually high throughput),</li>
 *   <li>the provider's flagged rate (share of claims not SUBMITTED/APPROVED, i.e.
 *       already flagged/under review),</li>
 *   <li>the provider's internal duplicate rate (near-identical repeat claims).</li>
 * </ul>
 */
public final class ProviderRiskService {

    private static final double W_AMOUNT = 0.40;
    private static final double W_VOLUME = 0.20;
    private static final double W_FLAGGED = 0.25;
    private static final double W_DUPLICATE = 0.15;

    private final ClaimRepository claims;
    private final DuplicateDetector duplicateDetector;

    public ProviderRiskService(ClaimRepository claims, DuplicateDetector duplicateDetector) {
        this.claims = claims;
        this.duplicateDetector = duplicateDetector;
    }

    /** Result of a provider-risk computation. */
    public static final class Result {
        public final double riskScore;      // [0,1]
        public final double avgAmount;
        public final double populationAvg;
        public final int claimCount;
        public final double flaggedRate;    // [0,1]
        public final double duplicateRate;  // [0,1]

        Result(double riskScore, double avgAmount, double populationAvg, int claimCount,
               double flaggedRate, double duplicateRate) {
            this.riskScore = riskScore;
            this.avgAmount = avgAmount;
            this.populationAvg = populationAvg;
            this.claimCount = claimCount;
            this.flaggedRate = flaggedRate;
            this.duplicateRate = duplicateRate;
        }
    }

    public Result assess(String providerId) {
        List<Claim> all = claims.findAll();
        List<Claim> providerClaims = claims.findByProviderId(providerId);
        if (providerClaims.isEmpty()) {
            return new Result(0.0, 0.0, mean(all), 0, 0.0, 0.0);
        }

        double providerAvg = mean(providerClaims);
        double populationAvg = mean(all);

        double amountFactor = populationAvg == 0.0
                ? 0.0
                : clamp01((providerAvg - populationAvg) / populationAvg);

        double avgCount = all.isEmpty() ? 0.0 : (double) all.size() / distinctProviderCount(all);
        double volumeFactor = avgCount == 0.0
                ? 0.0
                : clamp01((providerClaims.size() - avgCount) / Math.max(avgCount, 1.0));

        long flagged = providerClaims.stream().filter(ProviderRiskService::isFlagged).count();
        double flaggedRate = (double) flagged / providerClaims.size();

        double duplicateRate = internalDuplicateRate(providerClaims);

        double risk = clamp01(
                W_AMOUNT * amountFactor
                        + W_VOLUME * volumeFactor
                        + W_FLAGGED * flaggedRate
                        + W_DUPLICATE * duplicateRate);

        return new Result(risk, providerAvg, populationAvg, providerClaims.size(),
                flaggedRate, duplicateRate);
    }

    private double internalDuplicateRate(List<Claim> providerClaims) {
        if (providerClaims.size() < 2) {
            return 0.0;
        }
        int duplicatePairs = 0;
        int comparisons = 0;
        for (int i = 0; i < providerClaims.size(); i++) {
            for (int j = i + 1; j < providerClaims.size(); j++) {
                comparisons++;
                if (duplicateDetector.similarity(providerClaims.get(i), providerClaims.get(j)) >= 0.85) {
                    duplicatePairs++;
                }
            }
        }
        return comparisons == 0 ? 0.0 : (double) duplicatePairs / comparisons;
    }

    private static boolean isFlagged(Claim c) {
        String status = c.getClaimStatus();
        if (status == null) {
            return false;
        }
        return !"SUBMITTED".equalsIgnoreCase(status) && !"APPROVED".equalsIgnoreCase(status);
    }

    private static int distinctProviderCount(List<Claim> all) {
        return (int) all.stream().map(Claim::getProviderId).distinct().count();
    }

    private static double mean(List<Claim> list) {
        if (list.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Claim c : list) {
            sum += toDouble(c.getTotalBilledAmount());
        }
        return sum / list.size();
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
