package com.frauddetector.service.ml;

import com.frauddetector.service.FeatureService.FeatureVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Labeled training data for the fraud model (PRD sections 9, 25).
 *
 * <p>No public labeled medical-claims dataset can be bundled with the project,
 * so the base dataset is a deterministic synthetic population that encodes the
 * fraud patterns described in the PRD (section 2): inflated charges, duplicate
 * claims, unnecessary long stays / room padding, and abnormally frequent
 * claims, mixed with realistic legitimate claims and a small amount of label
 * noise. Investigator decisions are added on top as real labels
 * ({@link #feedbackSample}), which is the PRD's feedback / model-improvement
 * loop (section 6).
 */
public final class TrainingDataset {

    /** One labeled, normalized example. {@code weight} lets feedback count more. */
    public record Sample(double[] x, int label, double weight, String source) {
    }

    private TrainingDataset() {
    }

    /**
     * Generate {@code n} synthetic samples with roughly {@code fraudRate} fraud,
     * using a fixed {@code seed} so training is reproducible.
     */
    public static List<Sample> synthetic(int n, double fraudRate, long seed) {
        Random r = new Random(seed);
        List<Sample> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boolean fraud = r.nextDouble() < fraudRate;
            FeatureVector f = fraud ? fraudulent(r) : legitimate(r);
            int label = fraud ? 1 : 0;
            if (r.nextDouble() < 0.02) {
                label = 1 - label; // label noise, as in real investigation outcomes
            }
            out.add(new Sample(FraudClassifier.normalize(f), label, 1.0, "synthetic"));
        }
        return out;
    }

    /** Synthetic legitimate claims only (used to fit the isolation forest's "normal"). */
    public static List<double[]> syntheticNormal(int n, long seed) {
        Random r = new Random(seed);
        List<double[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(FraudClassifier.normalize(legitimate(r)));
        }
        return out;
    }

    /** Wrap an investigator-labeled claim as a training sample (weighted x5). */
    public static Sample feedbackSample(FeatureVector f, int label) {
        return new Sample(FraudClassifier.normalize(f), label, 5.0, "feedback");
    }

    private static FeatureVector legitimate(Random r) {
        FeatureVector f = new FeatureVector();
        f.providerAmountDeviation = r.nextGaussian() * 0.2;
        f.procedureAmountDeviation = r.nextGaussian() * 0.2;
        f.duplicateSimilarity = r.nextDouble() * 0.55;
        f.treatmentDurationDays = Math.floor(-Math.log(1 - r.nextDouble()) * 2.5);
        f.patientClaimFrequency = r.nextInt(3);
        f.roomRatio = 0.05 + r.nextDouble() * 0.4;
        return f;
    }

    private static FeatureVector fraudulent(Random r) {
        FeatureVector f = legitimate(r);
        boolean subtle = r.nextDouble() < 0.25;
        double s = subtle ? 0.5 : 1.0; // subtle fraud is harder to separate
        switch (r.nextInt(5)) {
            case 0: // inflated billing
                f.procedureAmountDeviation = s * (0.6 + r.nextDouble() * 2.4);
                f.providerAmountDeviation = s * (0.4 + r.nextDouble() * 2.0);
                break;
            case 1: // duplicate / near-duplicate submission
                f.duplicateSimilarity = subtle ? 0.7 + r.nextDouble() * 0.15 : 0.85 + r.nextDouble() * 0.15;
                break;
            case 2: // unnecessary long stay with room padding
                f.treatmentDurationDays = s * (15 + r.nextDouble() * 30);
                f.roomRatio = 0.45 + s * r.nextDouble() * 0.4;
                break;
            case 3: // abnormally frequent claims
                f.patientClaimFrequency = subtle ? 3 + r.nextInt(2) : 4 + r.nextInt(6);
                f.procedureAmountDeviation = 0.1 + r.nextDouble() * 0.8;
                break;
            default: // padded component charges
                f.roomRatio = 0.55 + s * r.nextDouble() * 0.35;
                f.procedureAmountDeviation = s * (0.3 + r.nextDouble() * 1.0);
                break;
        }
        return f;
    }
}
