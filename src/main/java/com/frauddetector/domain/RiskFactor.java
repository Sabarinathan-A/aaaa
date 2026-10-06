package com.frauddetector.domain;

/**
 * A single Explainable-AI reason contributing to a claim's fraud risk score
 * (PRD section 17). Produced from actual feature values and scorer outputs, not
 * hard-coded strings, so each factor carries a human-readable {@code description}
 * and a numeric {@code contribution} (its relative weight toward the final
 * score, in the range [0,1]).
 */
public final class RiskFactor {

    private final String description;
    private final double contribution;

    public RiskFactor(String description, double contribution) {
        this.description = description;
        this.contribution = contribution;
    }

    public String getDescription() {
        return description;
    }

    public double getContribution() {
        return contribution;
    }
}
