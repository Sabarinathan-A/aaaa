package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.Investigation;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.service.FeatureService.FeatureVector;
import com.frauddetector.service.ml.DuplicateDetector;
import com.frauddetector.service.ml.FraudClassifier;
import com.frauddetector.service.ml.IsolationForest;
import com.frauddetector.service.ml.LogisticModel;
import com.frauddetector.service.ml.ModelTrainer;
import com.frauddetector.service.ml.TrainingDataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model lifecycle (PRD sections 6, 9, 10, 25, 27): trains the logistic-regression
 * fraud classifier and fits the Isolation Forest, keeps the active models used by
 * {@link FraudRiskService}, and retrains on demand with investigator decisions
 * as extra labels (the feedback / model-improvement loop).
 *
 * <p>Feedback labels: REJECT and MARK_SUSPICIOUS = fraud (1); APPROVE and
 * FALSE_POSITIVE = legitimate (0). ESCALATE / REQUEST_DOCUMENTS are not final
 * and are ignored.
 */
public final class MlService {

    private static final int SYNTHETIC_SIZE = 2000;
    private static final double SYNTHETIC_FRAUD_RATE = 0.25;
    private static final long SEED = 20260601L;

    private final ClaimRepository claims;
    private final InvestigationRepository investigations;
    private final FeatureService featureService;
    private final DuplicateDetector duplicateDetector;
    private final ModelTrainer trainer = new ModelTrainer();

    private volatile LogisticModel model;
    private volatile IsolationForest forest;
    private int version;
    private final List<Map<String, Object>> history = new ArrayList<>();

    public MlService(ClaimRepository claims, PatientRepository patients, InvestigationRepository investigations) {
        this.claims = claims;
        this.investigations = investigations;
        this.featureService = new FeatureService(claims, patients);
        this.duplicateDetector = new DuplicateDetector(claims);
    }

    /** Train (or retrain) both models. Synchronized so concurrent retrains don't interleave. */
    public synchronized LogisticModel train() {
        List<TrainingDataset.Sample> samples = new ArrayList<>(
                TrainingDataset.synthetic(SYNTHETIC_SIZE, SYNTHETIC_FRAUD_RATE, SEED));
        List<TrainingDataset.Sample> feedback = feedbackSamples();
        samples.addAll(feedback);
        version++;
        LogisticModel trained = trainer.train(samples, "logreg-trained-v" + version, feedback.size());

        List<double[]> normal = new ArrayList<>(TrainingDataset.syntheticNormal(1500, SEED + 1));
        for (Claim c : claims.findAll()) {
            normal.add(FraudClassifier.normalize(features(c)));
        }
        IsolationForest fitted = IsolationForest.fit(normal, 100, 256, SEED + version);

        this.model = trained;
        this.forest = fitted;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", trained.version());
        entry.put("trainedAt", trained.trainedAt().toString());
        entry.put("feedbackSamples", feedback.size());
        entry.put("metrics", trained.metrics().toJson());
        history.add(0, entry);
        if (history.size() > 20) {
            history.remove(history.size() - 1);
        }
        return trained;
    }

    public LogisticModel model() {
        return model;
    }

    public IsolationForest forest() {
        return forest;
    }

    /** Investigator decisions turned into labeled samples. */
    public List<TrainingDataset.Sample> feedbackSamples() {
        List<TrainingDataset.Sample> out = new ArrayList<>();
        for (Investigation inv : investigations.findAll()) {
            Integer label = labelFor(inv.getDecision());
            if (label == null) {
                continue;
            }
            claims.findById(inv.getClaimId())
                    .ifPresent(c -> out.add(TrainingDataset.feedbackSample(features(c), label)));
        }
        return out;
    }

    static Integer labelFor(Investigation.Decision d) {
        if (d == null) {
            return null;
        }
        switch (d) {
            case REJECT:
            case MARK_SUSPICIOUS:
                return 1;
            case APPROVE:
            case FALSE_POSITIVE:
                return 0;
            default:
                return null;
        }
    }

    private FeatureVector features(Claim c) {
        FeatureVector f = featureService.extract(c, true);
        f.duplicateSimilarity = duplicateDetector.detect(c).similarity;
        return f;
    }

    /** Model card for the API. */
    public synchronized Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        LogisticModel current = model;
        if (current == null) {
            m.put("active", FraudClassifier.MODEL_VERSION);
            m.put("trained", false);
            return m;
        }
        m.put("active", current.version());
        m.put("trained", true);
        m.put("algorithm", "Logistic regression (gradient descent, L2, class-balanced)");
        m.put("trainedAt", current.trainedAt().toString());
        m.put("trainSize", current.trainSize());
        m.put("testSize", current.testSize());
        m.put("feedbackSamples", current.feedbackSamples());
        m.put("features", FraudClassifier.FEATURE_NAMES);
        m.put("weights", current.namedWeights());
        m.put("bias", Math.round(current.bias() * 10000.0) / 10000.0);
        m.put("metrics", current.metrics().toJson());
        Map<String, Object> iso = new LinkedHashMap<>();
        iso.put("algorithm", "Isolation Forest");
        iso.put("trees", forest == null ? 0 : forest.treeCount());
        iso.put("trainedOn", forest == null ? 0 : forest.trainedOn());
        m.put("anomalyModel", iso);
        m.put("pendingFeedbackSamples", feedbackSamples().size());
        m.put("history", new ArrayList<>(history));
        return m;
    }
}
