package com.frauddetector.service.ml;

import com.frauddetector.testkit.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Proves the trained fraud model actually learns (hold-out recall, precision
 * and ROC-AUC well above chance), that the metric maths is correct on a known
 * confusion matrix, and that the Isolation Forest scores an outlier above a
 * typical point. Run via {@code ./build.sh test}.
 */
public final class ModelTrainingTest {

    public static void main(String[] args) {
        testMetricsMath();
        testTrainedModelLearns();
        testFeedbackSamplesShiftModel();
        testIsolationForestFlagsOutlier();
        System.out.println("ModelTrainingTest OK");
    }

    private static void testMetricsMath() {
        // 3 TP, 1 FN, 1 FP, 5 TN
        double[] p = {0.9, 0.8, 0.7, 0.2, 0.6, 0.1, 0.1, 0.2, 0.3, 0.4};
        int[] y = {1, 1, 1, 1, 0, 0, 0, 0, 0, 0};
        ModelMetrics m = ModelMetrics.evaluate(p, y, 0.5);
        Assert.assertEquals(3, m.tp(), "tp");
        Assert.assertEquals(1, m.fn(), "fn");
        Assert.assertEquals(1, m.fp(), "fp");
        Assert.assertEquals(5, m.tn(), "tn");
        Assert.assertEquals(0.75, m.recall(), "recall = 3/4");
        Assert.assertEquals(0.75, m.precision(), "precision = 3/4");
        Assert.assertEquals(0.8, m.accuracy(), "accuracy = 8/10");
        // Pairs where positive > negative: 6+6+6+2, plus one tie (0.2 vs 0.2) = 0.5 -> 20.5/24
        Assert.assertEquals(0.8542, m.rocAuc(), "roc-auc");
        double[] perfect = {0.9, 0.8, 0.1, 0.2};
        int[] py = {1, 1, 0, 0};
        Assert.assertEquals(1.0, ModelMetrics.evaluate(perfect, py, 0.5).rocAuc(), "perfect ranking AUC = 1");
    }

    private static void testTrainedModelLearns() {
        List<TrainingDataset.Sample> data = TrainingDataset.synthetic(2000, 0.25, 11L);
        LogisticModel m = new ModelTrainer().train(data, "test-v1", 0);
        ModelMetrics x = m.metrics();
        Assert.assertTrue(x.recall() >= 0.80, "recall should be >= 0.80 but was " + x.recall());
        Assert.assertTrue(x.precision() >= 0.70, "precision should be >= 0.70 but was " + x.precision());
        Assert.assertTrue(x.rocAuc() >= 0.90, "ROC-AUC should be >= 0.90 but was " + x.rocAuc());
        Assert.assertEquals(400, m.testSize(), "20% hold-out");
        // Learned coefficients point the right way for the fraud signals.
        for (int i = 0; i < m.weights().length; i++) {
            Assert.assertTrue(m.weights()[i] > 0, FraudClassifier.FEATURE_NAMES.get(i) + " weight should be positive");
        }
        double[] clean = {0.0, 0.0, 0.2, 0.05, 0.0, 0.25};
        double[] inflatedDuplicate = {0.8, 0.9, 0.95, 0.1, 0.2, 0.3};
        Assert.assertTrue(m.predict(clean) < 0.3, "clean claim probability low: " + m.predict(clean));
        Assert.assertTrue(m.predict(inflatedDuplicate) > 0.9, "inflated duplicate probability high: " + m.predict(inflatedDuplicate));
    }

    private static void testFeedbackSamplesShiftModel() {
        List<TrainingDataset.Sample> base = TrainingDataset.synthetic(600, 0.25, 5L);
        LogisticModel before = new ModelTrainer().train(base, "v1", 0);
        // Investigators repeatedly confirm that a long-stay pattern is fraud.
        List<TrainingDataset.Sample> withFeedback = new ArrayList<>(base);
        double[] pattern = {0.0, 0.0, 0.1, 0.5, 0.0, 0.3};
        for (int i = 0; i < 40; i++) {
            withFeedback.add(new TrainingDataset.Sample(pattern, 1, 5.0, "feedback"));
        }
        LogisticModel after = new ModelTrainer().train(withFeedback, "v2", 40);
        Assert.assertTrue(after.predict(pattern) > before.predict(pattern),
                "feedback should raise the probability of the confirmed pattern");
        Assert.assertEquals(40, after.feedbackSamples(), "feedback count recorded");
    }

    private static void testIsolationForestFlagsOutlier() {
        Random r = new Random(3);
        List<double[]> normal = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            normal.add(new double[] {r.nextGaussian() * 0.05, r.nextGaussian() * 0.05, 0.2 + r.nextDouble() * 0.2});
        }
        IsolationForest f = IsolationForest.fit(normal, 100, 256, 1L);
        double typical = f.score(new double[] {0.0, 0.0, 0.3});
        double outlier = f.score(new double[] {0.9, 0.8, 0.95});
        Assert.assertTrue(outlier > typical, "outlier " + outlier + " should score above typical " + typical);
        Assert.assertTrue(outlier > 0.6, "outlier score should exceed 0.6: " + outlier);
        Assert.assertTrue(typical < 0.55, "typical score should be near 0.5: " + typical);
        Assert.assertEquals(100, f.treeCount(), "tree count");
    }
}
