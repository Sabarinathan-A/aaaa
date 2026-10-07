package com.frauddetector.service.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Isolation Forest anomaly detector (PRD section 10; Liu, Ting & Zhou, 2008).
 *
 * <p>Each tree recursively partitions a random subsample with random splits on
 * random features. Anomalies are isolated in fewer splits, so a short average
 * path length means "unusual". The score is
 * {@code s(x) = 2^(-E[h(x)] / c(psi))}, where {@code c(psi)} is the average path
 * length of an unsuccessful BST search; {@code s ~ 0.5} is normal and values
 * approaching 1 are anomalous.
 */
public final class IsolationForest {

    private final List<Node> trees;
    private final int sampleSize;
    private final int trainedOn;

    private IsolationForest(List<Node> trees, int sampleSize, int trainedOn) {
        this.trees = trees;
        this.sampleSize = sampleSize;
        this.trainedOn = trainedOn;
    }

    /** Fit {@code nTrees} trees on subsamples of size {@code psi}. */
    public static IsolationForest fit(List<double[]> data, int nTrees, int psi, long seed) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("Isolation forest needs data");
        }
        Random r = new Random(seed);
        int sample = Math.min(psi, data.size());
        int heightLimit = (int) Math.ceil(Math.log(sample) / Math.log(2));
        List<Node> trees = new ArrayList<>(nTrees);
        for (int t = 0; t < nTrees; t++) {
            List<double[]> subsample = new ArrayList<>(sample);
            for (int i = 0; i < sample; i++) {
                subsample.add(data.get(r.nextInt(data.size())));
            }
            trees.add(build(subsample, 0, heightLimit, r));
        }
        return new IsolationForest(trees, sample, data.size());
    }

    /** Anomaly score in (0,1); ~0.5 normal, &gt;0.6 increasingly anomalous. */
    public double score(double[] x) {
        double total = 0.0;
        for (Node tree : trees) {
            total += pathLength(x, tree, 0);
        }
        double avg = total / trees.size();
        return Math.pow(2.0, -avg / c(sampleSize));
    }

    /** Map the raw score onto [0,1] for blending: 0.5 -&gt; 0, 0.75+ -&gt; 1. */
    public double normalizedScore(double[] x) {
        double s = score(x);
        return Math.max(0.0, Math.min(1.0, (s - 0.5) / 0.25));
    }

    public int treeCount() {
        return trees.size();
    }

    public int trainedOn() {
        return trainedOn;
    }

    private static Node build(List<double[]> data, int depth, int limit, Random r) {
        if (depth >= limit || data.size() <= 1) {
            return Node.leaf(data.size());
        }
        int dims = data.get(0).length;
        // Pick a feature that actually varies in this partition.
        for (int attempt = 0; attempt < dims * 2; attempt++) {
            int q = r.nextInt(dims);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double[] v : data) {
                min = Math.min(min, v[q]);
                max = Math.max(max, v[q]);
            }
            if (max - min < 1e-12) {
                continue;
            }
            double split = min + r.nextDouble() * (max - min);
            List<double[]> left = new ArrayList<>();
            List<double[]> right = new ArrayList<>();
            for (double[] v : data) {
                (v[q] < split ? left : right).add(v);
            }
            return Node.split(q, split, build(left, depth + 1, limit, r), build(right, depth + 1, limit, r));
        }
        return Node.leaf(data.size());
    }

    private static double pathLength(double[] x, Node node, int depth) {
        if (node.leaf) {
            return depth + c(node.size);
        }
        return x[node.feature] < node.split
                ? pathLength(x, node.left, depth + 1)
                : pathLength(x, node.right, depth + 1);
    }

    /** Average path length of an unsuccessful search in a BST of n nodes. */
    static double c(int n) {
        if (n <= 1) {
            return 0.0;
        }
        if (n == 2) {
            return 1.0;
        }
        double harmonic = Math.log(n - 1.0) + 0.5772156649;
        return 2.0 * harmonic - 2.0 * (n - 1.0) / n;
    }

    private static final class Node {
        final boolean leaf;
        final int size;
        final int feature;
        final double split;
        final Node left;
        final Node right;

        private Node(boolean leaf, int size, int feature, double split, Node left, Node right) {
            this.leaf = leaf;
            this.size = size;
            this.feature = feature;
            this.split = split;
            this.left = left;
            this.right = right;
        }

        static Node leaf(int size) {
            return new Node(true, size, -1, 0, null, null);
        }

        static Node split(int feature, double split, Node left, Node right) {
            return new Node(false, 0, feature, split, left, right);
        }
    }
}
