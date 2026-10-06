package com.frauddetector.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The persisted result of running the fraud-detection engine against a claim
 * (PRD sections 15-17, 29). Holds the individual layer scores, the combined
 * final risk score (0-100), the derived risk level, the model version, and the
 * ordered list of Explainable-AI {@link RiskFactor}s that justify the score.
 *
 * <p>All layer scores except {@code finalRiskScore} are normalized to [0,1];
 * {@code finalRiskScore} is on the PRD 0-100 scale.
 */
public final class FraudAnalysis {

    private final String analysisId;
    private final String claimId;
    private final double fraudProbability;
    private final double billingAnomalyScore;
    private final double duplicateScore;
    private final double providerRiskScore;
    private final double patientRiskScore;
    private final double finalRiskScore;
    private final String riskLevel;
    private final String modelVersion;
    private final List<RiskFactor> riskFactors;
    private final Instant createdAt;

    public FraudAnalysis(String analysisId, String claimId, double fraudProbability,
                         double billingAnomalyScore, double duplicateScore,
                         double providerRiskScore, double patientRiskScore,
                         double finalRiskScore, String riskLevel, String modelVersion,
                         List<RiskFactor> riskFactors, Instant createdAt) {
        this.analysisId = analysisId;
        this.claimId = claimId;
        this.fraudProbability = fraudProbability;
        this.billingAnomalyScore = billingAnomalyScore;
        this.duplicateScore = duplicateScore;
        this.providerRiskScore = providerRiskScore;
        this.patientRiskScore = patientRiskScore;
        this.finalRiskScore = finalRiskScore;
        this.riskLevel = riskLevel;
        this.modelVersion = modelVersion;
        this.riskFactors = riskFactors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(riskFactors));
        this.createdAt = createdAt;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public String getClaimId() {
        return claimId;
    }

    public double getFraudProbability() {
        return fraudProbability;
    }

    public double getBillingAnomalyScore() {
        return billingAnomalyScore;
    }

    public double getDuplicateScore() {
        return duplicateScore;
    }

    public double getProviderRiskScore() {
        return providerRiskScore;
    }

    public double getPatientRiskScore() {
        return patientRiskScore;
    }

    public double getFinalRiskScore() {
        return finalRiskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public List<RiskFactor> getRiskFactors() {
        return riskFactors;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
