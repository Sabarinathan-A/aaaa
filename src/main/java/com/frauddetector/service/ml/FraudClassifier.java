package com.frauddetector.service.ml;

import com.frauddetector.service.FeatureService.FeatureVector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transparent, deterministic fraud classifier (PRD Option B, dependency-free).
 *
 * <p>Because no training library (scikit-learn / XGBoost / Tribuo / Weka) can be
 * downloaded in this sandbox, the supervised model is implemented as an explicit
 * logistic-regression-style scorer: each feature is normalized into roughly
 * [0,1], multiplied by a documented constant weight, summed with a bias, and
 * passed through a sigmoid to yield a fraud probability in [0,1]. The constant
 * weights stand in for the coefficients a trained Random Forest / gradient-boosted
 * model would learn, and are chosen so that inflated bills, abnormal treatment
 * durations, and near-duplicate claims push the probability up.
 *
 * <p>Every feature's individual contribution (weight * normalized value) is
 * recorded so {@code FraudRiskService} can build Explainable-AI risk factors.
 */
public final class FraudClassifier {

    /** Model version string stamped onto each {@code FraudAnalysis}. */
    public static final String MODEL_VERSION = "logreg-stub-v1";

    // Documented stand-in coefficients (one per normalized feature) + bias.
    private static final double BIAS = -2.2;
    private static final double W_PROVIDER_DEVIATION = 2.6;  // bill far above provider norm
    private static final double W_PROCEDURE_DEVIATION = 2.2; // bill far above procedure norm
    private static final double W_DUPLICATE = 2.8;           // near-duplicate claim
    private static final double W_DURATION = 1.2;            // abnormal stay length
    private static final double W_FREQUENCY = 1.0;           // high claim frequency
    private static final double W_ROOM_RATIO = 0.8;          // room charges dominate the bill

    public static final class Result {
        public final double fraudProbability;          // [0,1]
        public final Map<String, Double> contributions; // feature -> weight*normalizedValue

        Result(double fraudProbability, Map<String, Double> contributions) {
            this.fraudProbability = fraudProbability;
            this.contributions = contributions;
        }
    }

    /**
     * Classify a feature vector. {@code duplicateSimilarity} is read from the
     * vector, so callers should populate it (via {@link DuplicateDetector})
     * before calling.
     */
    public Result classify(FeatureVector f) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        // Normalize each raw feature into roughly [0,1] (deviations are already
        // ratios; durations/frequencies are scaled by plausible maxima).
        normalized.put("providerAmountDeviation", normDeviation(f.providerAmountDeviation));
        normalized.put("procedureAmountDeviation", normDeviation(f.procedureAmountDeviation));
        normalized.put("duplicateSimilarity", clamp01(f.duplicateSimilarity));
        normalized.put("treatmentDurationDays", normDuration(f.treatmentDurationDays));
        normalized.put("patientClaimFrequency", clamp01(f.patientClaimFrequency / 5.0));
        normalized.put("roomRatio", clamp01(f.roomRatio));

        Map<String, Double> contributions = new LinkedHashMap<>();
        contributions.put("providerAmountDeviation",
                W_PROVIDER_DEVIATION * normalized.get("providerAmountDeviation"));
        contributions.put("procedureAmountDeviation",
                W_PROCEDURE_DEVIATION * normalized.get("procedureAmountDeviation"));
        contributions.put("duplicateSimilarity",
                W_DUPLICATE * normalized.get("duplicateSimilarity"));
        contributions.put("treatmentDurationDays",
                W_DURATION * normalized.get("treatmentDurationDays"));
        contributions.put("patientClaimFrequency",
                W_FREQUENCY * normalized.get("patientClaimFrequency"));
        contributions.put("roomRatio",
                W_ROOM_RATIO * normalized.get("roomRatio"));

        double z = BIAS;
        for (double c : contributions.values()) {
            z += c;
        }
        return new Result(sigmoid(z), contributions);
    }

    /** Map a signed deviation ratio to [0,1]; only positive (above-norm) matters. */
    private static double normDeviation(double deviation) {
        return clamp01(Math.max(0.0, deviation) / 2.0); // +200% deviation saturates
    }

    /** Treatment durations beyond ~30 days are increasingly abnormal. */
    private static double normDuration(double days) {
        return clamp01(Math.max(0.0, days) / 30.0);
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
