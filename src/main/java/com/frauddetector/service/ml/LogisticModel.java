package com.frauddetector.service.ml;

import java.time.Instant;
import java.util.Map;

/**
 * A trained logistic-regression fraud classifier (PRD sections 9, 25). Weights
 * are learned by {@link ModelTrainer} from a labeled dataset; {@link #predict}
 * returns the fraud probability {@code sigmoid(bias + w . x)} for a normalized
 * input vector in {@link FraudClassifier#FEATURE_NAMES} order.
 *
 * @param version   model version stamped on each analysis (e.g. {@code logreg-trained-v2})
 * @param weights   learned coefficients, one per feature
 * @param bias      learned intercept
 * @param metrics   hold-out evaluation (see {@link ModelMetrics})
 * @param trainedAt training timestamp
 * @param trainSize number of training samples
 * @param testSize  number of hold-out samples
 * @param feedbackSamples number of investigator-labeled samples included
 */
public record LogisticModel(String version, double[] weights, double bias, ModelMetrics metrics,
                            Instant trainedAt, int trainSize, int testSize, int feedbackSamples) {

    public double predict(double[] x) {
        double z = bias;
        for (int i = 0; i < weights.length; i++) {
            z += weights[i] * x[i];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** Coefficients keyed by feature name (for the API / explanations). */
    public Map<String, Double> namedWeights() {
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < weights.length; i++) {
            m.put(FraudClassifier.FEATURE_NAMES.get(i), Math.round(weights[i] * 10000.0) / 10000.0);
        }
        return m;
    }
}
