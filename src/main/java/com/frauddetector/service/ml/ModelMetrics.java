package com.frauddetector.service.ml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classification metrics on a hold-out set (PRD section 27): accuracy,
 * precision, recall, F1, ROC-AUC and the confusion matrix at a decision
 * threshold. Recall is the headline metric for fraud (missed fraud is costly);
 * precision guards investigator workload.
 */
public record ModelMetrics(double accuracy, double precision, double recall, double f1, double rocAuc,
                           double falsePositiveRate, int tp, int fp, int tn, int fn, double threshold) {

    /** Compute metrics from predicted probabilities and true 0/1 labels. */
    public static ModelMetrics evaluate(double[] probs, int[] labels, double threshold) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (int i = 0; i < probs.length; i++) {
            boolean predicted = probs[i] >= threshold;
            boolean actual = labels[i] == 1;
            if (predicted && actual) {
                tp++;
            } else if (predicted) {
                fp++;
            } else if (actual) {
                fn++;
            } else {
                tn++;
            }
        }
        int n = probs.length;
        double accuracy = n == 0 ? 0 : (tp + tn) / (double) n;
        double precision = tp + fp == 0 ? 0 : tp / (double) (tp + fp);
        double recall = tp + fn == 0 ? 0 : tp / (double) (tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        double fpr = fp + tn == 0 ? 0 : fp / (double) (fp + tn);
        return new ModelMetrics(r4(accuracy), r4(precision), r4(recall), r4(f1), r4(rocAuc(probs, labels)),
                r4(fpr), tp, fp, tn, fn, threshold);
    }

    /** ROC-AUC via the Mann-Whitney rank statistic (ties get average rank). */
    static double rocAuc(double[] probs, int[] labels) {
        int n = probs.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(probs[a], probs[b]));
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && probs[idx[j + 1]] == probs[idx[i]]) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                ranks[idx[k]] = avg;
            }
            i = j + 1;
        }
        long pos = 0, neg = 0;
        double sumPos = 0;
        for (int k = 0; k < n; k++) {
            if (labels[k] == 1) {
                pos++;
                sumPos += ranks[k];
            } else {
                neg++;
            }
        }
        if (pos == 0 || neg == 0) {
            return 0.0;
        }
        return (sumPos - pos * (pos + 1) / 2.0) / (pos * (double) neg);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accuracy", accuracy);
        m.put("precision", precision);
        m.put("recall", recall);
        m.put("f1", f1);
        m.put("rocAuc", rocAuc);
        m.put("falsePositiveRate", falsePositiveRate);
        m.put("threshold", threshold);
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("truePositive", tp);
        cm.put("falsePositive", fp);
        cm.put("trueNegative", tn);
        cm.put("falseNegative", fn);
        m.put("confusionMatrix", cm);
        m.put("confusionMatrixRows", matrix());
        return m;
    }

    private static double r4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Confusion matrix as rows [[tn, fp], [fn, tp]] (actual x predicted). */
    public List<List<Integer>> matrix() {
        List<List<Integer>> rows = new ArrayList<>();
        rows.add(List.of(tn, fp));
        rows.add(List.of(fn, tp));
        return rows;
    }
}
