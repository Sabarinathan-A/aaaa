package com.frauddetector.service.ml;

import com.frauddetector.domain.Claim;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.service.FeatureService;
import com.frauddetector.service.FeatureService.FeatureVector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unsupervised-style anomaly detection (an Isolation-Forest stand-in built from
 * statistics, since no ML training libraries are available). For a chosen set of
 * features it learns the mean and standard deviation across the historical claim
 * population held in the {@link ClaimRepository}, computes the z-score of the
 * target claim's features against that distribution, and combines the per-feature
 * deviations into a single anomaly score in [0,1]. Claims far from the learned
 * "normal" distribution score high.
 */
public final class AnomalyDetector {

    // Features whose distribution across the population is meaningful to compare.
    private static final List<String> SCORED_FEATURES = List.of(
            "totalAmount",
            "treatmentDurationDays",
            "medicineRatio",
            "roomRatio",
            "doctorRatio",
            "providerAmountDeviation");

    private final ClaimRepository claims;
    private final FeatureService featureService;

    public AnomalyDetector(ClaimRepository claims, PatientRepository patients) {
        this.claims = claims;
        this.featureService = new FeatureService(claims, patients);
    }

    /** Result of an anomaly scan, including the per-feature z-scores. */
    public static final class Result {
        public final double anomalyScore;              // [0,1]
        public final Map<String, Double> featureZScores;

        Result(double anomalyScore, Map<String, Double> featureZScores) {
            this.anomalyScore = anomalyScore;
            this.featureZScores = featureZScores;
        }
    }

    public Result score(Claim claim) {
        FeatureVector target = featureService.extract(claim, true);
        List<FeatureVector> population = new ArrayList<>();
        for (Claim c : claims.findAll()) {
            if (c.getClaimId().equals(claim.getClaimId())) {
                continue;
            }
            population.add(featureService.extract(c, true));
        }
        return score(target, population);
    }

    /** Core computation against an explicit population (exposed for tests). */
    public Result score(FeatureVector target, List<FeatureVector> population) {
        Map<String, Double> zScores = new LinkedHashMap<>();
        if (population.isEmpty()) {
            for (String f : SCORED_FEATURES) {
                zScores.put(f, 0.0);
            }
            return new Result(0.0, zScores);
        }

        double sumZ = 0.0;
        int counted = 0;
        for (String feature : SCORED_FEATURES) {
            double[] values = new double[population.size()];
            Map<String, Double> targetMap = target.asMap();
            for (int i = 0; i < population.size(); i++) {
                values[i] = population.get(i).asMap().get(feature);
            }
            double mean = mean(values);
            double stddev = stdDev(values, mean);
            double z;
            if (stddev > 1e-9) {
                z = Math.abs((targetMap.get(feature) - mean) / stddev);
            } else {
                z = 0.0;
            }
            zScores.put(feature, z);
            sumZ += z;
            counted++;
        }

        double avgZ = counted == 0 ? 0.0 : sumZ / counted;
        // Map average |z| through a saturating curve: avgZ=3 -> ~1.
        double anomalyScore = clamp01(avgZ / 3.0);
        return new Result(anomalyScore, zScores);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double stdDev(double[] values, double mean) {
        if (values.length < 2) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / values.length);
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
