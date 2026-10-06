package com.frauddetector.service.ml;

import com.frauddetector.domain.Claim;
import com.frauddetector.repository.ClaimRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Duplicate / near-duplicate claim detection (PRD section 12). Scores how
 * similar a new claim is to each existing claim across weighted dimensions:
 * same patient, same provider, date proximity, amount closeness, same diagnosis,
 * and same procedure. The highest similarity across the population is returned
 * as the duplicate probability [0,1], together with the id of the most similar
 * claim.
 */
public final class DuplicateDetector {

    // Dimension weights (sum = 1.0). Patient + provider identity and matching
    // date/amount dominate because true duplicates are the same event re-filed.
    private static final double W_PATIENT = 0.20;
    private static final double W_PROVIDER = 0.15;
    private static final double W_DATE = 0.20;
    private static final double W_AMOUNT = 0.20;
    private static final double W_DIAGNOSIS = 0.125;
    private static final double W_PROCEDURE = 0.125;

    // A date gap at/under this many days counts as "proximate".
    private static final long DATE_WINDOW_DAYS = 7;

    private final ClaimRepository claims;

    public DuplicateDetector(ClaimRepository claims) {
        this.claims = claims;
    }

    /** Result of a duplicate scan. */
    public static final class Result {
        public final double similarity;         // [0,1], the max over the population
        public final String mostSimilarClaimId; // null when there is no history
        public final long dayGap;                // date gap to the most similar claim

        Result(double similarity, String mostSimilarClaimId, long dayGap) {
            this.similarity = similarity;
            this.mostSimilarClaimId = mostSimilarClaimId;
            this.dayGap = dayGap;
        }
    }

    /** Scan the repository for the claim most similar to {@code claim}. */
    public Result detect(Claim claim) {
        return detect(claim, claims.findAll());
    }

    /** Core computation against an explicit candidate list (exposed for tests). */
    public Result detect(Claim claim, List<Claim> candidates) {
        double best = 0.0;
        String bestId = null;
        long bestGap = 0;
        for (Claim other : candidates) {
            if (other.getClaimId().equals(claim.getClaimId())) {
                continue;
            }
            double sim = similarity(claim, other);
            if (sim > best) {
                best = sim;
                bestId = other.getClaimId();
                bestGap = dayGap(claim, other);
            }
        }
        return new Result(best, bestId, bestGap);
    }

    /** Weighted similarity of two claims in [0,1]. */
    public double similarity(Claim a, Claim b) {
        double score = 0.0;
        score += W_PATIENT * (equalsIgnoreCaseSafe(a.getPatientId(), b.getPatientId()) ? 1.0 : 0.0);
        score += W_PROVIDER * (equalsIgnoreCaseSafe(a.getProviderId(), b.getProviderId()) ? 1.0 : 0.0);
        score += W_DATE * dateCloseness(a, b);
        score += W_AMOUNT * amountCloseness(a.getTotalBilledAmount(), b.getTotalBilledAmount());
        score += W_DIAGNOSIS * (equalsIgnoreCaseSafe(a.getDiagnosis(), b.getDiagnosis()) ? 1.0 : 0.0);
        score += W_PROCEDURE * (equalsIgnoreCaseSafe(a.getProcedure(), b.getProcedure()) ? 1.0 : 0.0);
        return score;
    }

    private static double dateCloseness(Claim a, Claim b) {
        long gap = dayGap(a, b);
        if (gap < 0) {
            return 0.0;
        }
        if (gap >= DATE_WINDOW_DAYS) {
            return 0.0;
        }
        // Linear decay: 0 days -> 1.0, DATE_WINDOW_DAYS -> 0.
        return 1.0 - ((double) gap / DATE_WINDOW_DAYS);
    }

    private static long dayGap(Claim a, Claim b) {
        LocalDate da = a.getClaimDate() != null ? a.getClaimDate() : a.getAdmissionDate();
        LocalDate db = b.getClaimDate() != null ? b.getClaimDate() : b.getAdmissionDate();
        if (da == null || db == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(ChronoUnit.DAYS.between(da, db));
    }

    private static double amountCloseness(BigDecimal a, BigDecimal b) {
        double x = a == null ? 0.0 : a.doubleValue();
        double y = b == null ? 0.0 : b.doubleValue();
        double max = Math.max(Math.abs(x), Math.abs(y));
        if (max == 0.0) {
            return 1.0;
        }
        double relDiff = Math.abs(x - y) / max;
        return Math.max(0.0, 1.0 - relDiff);
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }
}
