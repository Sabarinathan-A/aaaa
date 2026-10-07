package com.frauddetector.service.ml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Trains a {@link LogisticModel} with full-batch gradient descent on the
 * binary cross-entropy loss plus L2 regularization (PRD section 25: train/test
 * split, model training, evaluation). Classes are re-weighted so the minority
 * (fraud) class is not ignored. Deterministic for a given seed.
 */
public final class ModelTrainer {

    private final int epochs;
    private final double learningRate;
    private final double l2;
    private final double testFraction;
    private final long seed;

    public ModelTrainer() {
        this(2000, 0.8, 0.0005, 0.2, 42L);
    }

    public ModelTrainer(int epochs, double learningRate, double l2, double testFraction, long seed) {
        this.epochs = epochs;
        this.learningRate = learningRate;
        this.l2 = l2;
        this.testFraction = testFraction;
        this.seed = seed;
    }

    /**
     * Shuffle, split into train / hold-out, fit on train, evaluate on hold-out.
     *
     * @param samples labeled samples (normalized features)
     * @param version version string for the resulting model
     * @param feedbackCount how many of the samples came from investigator feedback
     */
    public LogisticModel train(List<TrainingDataset.Sample> samples, String version, int feedbackCount) {
        if (samples.size() < 10) {
            throw new IllegalArgumentException("Need at least 10 samples to train, got " + samples.size());
        }
        List<TrainingDataset.Sample> shuffled = new ArrayList<>(samples);
        Collections.shuffle(shuffled, new Random(seed));
        int testSize = Math.max(1, (int) Math.round(shuffled.size() * testFraction));
        List<TrainingDataset.Sample> test = shuffled.subList(0, testSize);
        List<TrainingDataset.Sample> train = shuffled.subList(testSize, shuffled.size());

        int d = train.get(0).x().length;
        double[] w = new double[d];
        double b = 0.0;

        long positives = train.stream().filter(s -> s.label() == 1).count();
        long negatives = train.size() - positives;
        // Balanced class weights: each class contributes equally to the loss.
        double wPos = positives == 0 ? 1.0 : train.size() / (2.0 * positives);
        double wNeg = negatives == 0 ? 1.0 : train.size() / (2.0 * negatives);

        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradW = new double[d];
            double gradB = 0.0;
            double totalWeight = 0.0;
            for (TrainingDataset.Sample s : train) {
                double z = b;
                for (int k = 0; k < d; k++) {
                    z += w[k] * s.x()[k];
                }
                double p = 1.0 / (1.0 + Math.exp(-z));
                double cw = (s.label() == 1 ? wPos : wNeg) * s.weight();
                double err = (p - s.label()) * cw;
                for (int k = 0; k < d; k++) {
                    gradW[k] += err * s.x()[k];
                }
                gradB += err;
                totalWeight += cw;
            }
            for (int k = 0; k < d; k++) {
                w[k] -= learningRate * (gradW[k] / totalWeight + l2 * w[k]);
            }
            b -= learningRate * (gradB / totalWeight);
        }

        LogisticModel provisional = new LogisticModel(version, w, b, null, Instant.now(),
                train.size(), test.size(), feedbackCount);
        double[] probs = new double[test.size()];
        int[] labels = new int[test.size()];
        for (int i = 0; i < test.size(); i++) {
            probs[i] = provisional.predict(test.get(i).x());
            labels[i] = test.get(i).label();
        }
        ModelMetrics metrics = ModelMetrics.evaluate(probs, labels, 0.5);
        return new LogisticModel(version, w, b, metrics, provisional.trainedAt(),
                train.size(), test.size(), feedbackCount);
    }
}
