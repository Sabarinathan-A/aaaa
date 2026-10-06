package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.RiskFactor;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.service.FeatureService.FeatureVector;
import com.frauddetector.service.ml.AnomalyDetector;
import com.frauddetector.service.ml.BillingAnomalyService;
import com.frauddetector.service.ml.DuplicateDetector;
import com.frauddetector.service.ml.FraudClassifier;
import com.frauddetector.service.ml.PatientRiskService;
import com.frauddetector.service.ml.ProviderRiskService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the multi-layer fraud engine (PRD sections 15-17). Runs each
 * scorer, combines their normalized outputs into a single final risk score on
 * the 0-100 scale using documented weights, classifies that score into a risk
 * level via the configurable {@link ThresholdConfig}, and builds an ordered list
 * of Explainable-AI {@link RiskFactor}s from the actual feature values and scorer
 * outputs (not hard-coded strings).
 *
 * <p>Combination weights (sum = 1.0) over the five layer scores:
 * <ul>
 *   <li>fraud probability (supervised stand-in): 0.30</li>
 *   <li>billing anomaly:                         0.25</li>
 *   <li>duplicate probability:                   0.20</li>
 *   <li>provider risk:                           0.15</li>
 *   <li>patient risk:                            0.10</li>
 * </ul>
 */
public final class FraudRiskService {

    private static final double W_FRAUD = 0.30;
    private static final double W_BILLING = 0.25;
    private static final double W_DUPLICATE = 0.20;
    private static final double W_PROVIDER = 0.15;
    private static final double W_PATIENT = 0.10;

    private final FeatureService featureService;
    private final FraudClassifier fraudClassifier;
    private final AnomalyDetector anomalyDetector;
    private final BillingAnomalyService billingAnomalyService;
    private final DuplicateDetector duplicateDetector;
    private final ProviderRiskService providerRiskService;
    private final PatientRiskService patientRiskService;
    private final ThresholdConfig thresholds;

    public FraudRiskService(ClaimRepository claims, PatientRepository patients,
                            ThresholdConfig thresholds) {
        this(claims, patients, null, thresholds);
    }

    /**
     * Full constructor. When an {@link com.frauddetector.repository.FraudAnalysisRepository}
     * is supplied, the billing layer excludes already-flagged claims from its
     * "normal" baseline in addition to near-duplicates, so a cluster of identical
     * inflated claims cannot define its own baseline (review findings #1/#2).
     */
    public FraudRiskService(ClaimRepository claims, PatientRepository patients,
                            com.frauddetector.repository.FraudAnalysisRepository analyses,
                            ThresholdConfig thresholds) {
        this.thresholds = thresholds;
        this.featureService = new FeatureService(claims, patients);
        this.fraudClassifier = new FraudClassifier();
        this.anomalyDetector = new AnomalyDetector(claims, patients);
        this.duplicateDetector = new DuplicateDetector(claims);
        this.billingAnomalyService =
                new BillingAnomalyService(claims, analyses, thresholds, duplicateDetector);
        this.providerRiskService = new ProviderRiskService(claims, duplicateDetector);
        this.patientRiskService = new PatientRiskService(claims);
    }

    /** Run every layer against {@code claim} and build the combined analysis. */
    public FraudAnalysis analyze(Claim claim) {
        // Feature extraction + duplicate scan (fills the duplicate feature).
        FeatureVector features = featureService.extract(claim, true);
        DuplicateDetector.Result duplicate = duplicateDetector.detect(claim);
        features.duplicateSimilarity = duplicate.similarity;

        // Layer scores.
        FraudClassifier.Result fraud = fraudClassifier.classify(features);
        AnomalyDetector.Result anomaly = anomalyDetector.score(claim);
        BillingAnomalyService.Result billing = billingAnomalyService.analyze(claim);
        ProviderRiskService.Result provider = providerRiskService.assess(claim.getProviderId());
        PatientRiskService.Result patient = patientRiskService.assess(claim);

        // Blend the supervised probability with the unsupervised anomaly score so
        // novel-but-not-yet-modeled patterns still raise the fraud component.
        double fraudProbability = clamp01(0.7 * fraud.fraudProbability + 0.3 * anomaly.anomalyScore);

        double combined =
                W_FRAUD * fraudProbability
                        + W_BILLING * billing.anomalyScore
                        + W_DUPLICATE * duplicate.similarity
                        + W_PROVIDER * provider.riskScore
                        + W_PATIENT * patient.riskScore;

        double finalScore = round1(clamp01(combined) * 100.0);
        String riskLevel = thresholds.classify(finalScore);

        List<RiskFactor> factors = buildRiskFactors(
                features, fraud, anomaly, billing, duplicate, provider, patient);

        return new FraudAnalysis(
                "FA-" + UUID.randomUUID(),
                claim.getClaimId(),
                round4(fraudProbability),
                round4(billing.anomalyScore),
                round4(duplicate.similarity),
                round4(provider.riskScore),
                round4(patient.riskScore),
                finalScore,
                riskLevel,
                FraudClassifier.MODEL_VERSION,
                factors,
                Instant.now());
    }

    public ThresholdConfig thresholds() {
        return thresholds;
    }

    /**
     * Build Explainable-AI risk factors from the real feature values and scorer
     * outputs, ordered by descending contribution. Only materially contributing
     * factors are emitted so the explanation stays focused.
     */
    private List<RiskFactor> buildRiskFactors(
            FeatureVector features,
            FraudClassifier.Result fraud,
            AnomalyDetector.Result anomaly,
            BillingAnomalyService.Result billing,
            DuplicateDetector.Result duplicate,
            ProviderRiskService.Result provider,
            PatientRiskService.Result patient) {

        List<RiskFactor> factors = new ArrayList<>();

        // Billing above historical average.
        if (billing.sampleSize > 0 && billing.percentAboveAverage >= 20.0) {
            factors.add(new RiskFactor(
                    String.format("Billing amount is %.0f%% above the historical average for this procedure",
                            billing.percentAboveAverage),
                    round4(W_BILLING * billing.anomalyScore)));
        }

        // Duplicate / near-duplicate claim.
        if (duplicate.similarity >= thresholds.getDuplicateThreshold() && duplicate.mostSimilarClaimId != null) {
            factors.add(new RiskFactor(
                    String.format("Similar claim detected (%s) within %d day(s), %.0f%% match",
                            duplicate.mostSimilarClaimId, duplicate.dayGap, duplicate.similarity * 100.0),
                    round4(W_DUPLICATE * duplicate.similarity)));
        }

        // Provider behavior.
        if (provider.riskScore >= 0.4) {
            factors.add(new RiskFactor(
                    String.format("Provider shows elevated risk (avg claim %.0f vs population %.0f, flagged rate %.0f%%)",
                            provider.avgAmount, provider.populationAvg, provider.flaggedRate * 100.0),
                    round4(W_PROVIDER * provider.riskScore)));
        }

        // Patient behavior.
        if (patient.riskScore >= 0.4) {
            factors.add(new RiskFactor(
                    String.format("Patient has unusual activity (%d claim(s) in window, %d repeated treatment(s), %d hospital(s))",
                            patient.claimsInWindow, patient.repeatedTreatments, patient.maxHospitalsForOneTreatment),
                    round4(W_PATIENT * patient.riskScore)));
        }

        // Treatment duration out of range (surfaced via the anomaly z-score).
        Double durationZ = anomaly.featureZScores.get("treatmentDurationDays");
        if (durationZ != null && durationZ >= 2.0) {
            factors.add(new RiskFactor(
                    String.format("Treatment duration (%.0f day(s)) is outside the normal range (z=%.1f)",
                            features.treatmentDurationDays, durationZ),
                    round4(W_FRAUD * anomaly.anomalyScore)));
        }

        // Statistical anomaly across the feature distribution.
        if (anomaly.anomalyScore >= 0.5) {
            factors.add(new RiskFactor(
                    String.format("Claim deviates from the normal population distribution (anomaly score %.2f)",
                            anomaly.anomalyScore),
                    round4(W_FRAUD * anomaly.anomalyScore)));
        }

        // Dominant supervised-model feature contribution, when nothing else fired.
        if (factors.isEmpty() && fraud.fraudProbability >= 0.5) {
            String topFeature = fraud.contributions.entrySet().stream()
                    .max(Comparator.comparingDouble(e -> e.getValue()))
                    .map(e -> e.getKey())
                    .orElse("model features");
            factors.add(new RiskFactor(
                    String.format("Fraud model flagged this claim (probability %.2f), driven by %s",
                            fraud.fraudProbability, topFeature),
                    round4(W_FRAUD * fraud.fraudProbability)));
        }

        factors.sort(Comparator.comparingDouble(RiskFactor::getContribution).reversed());
        return factors;
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
