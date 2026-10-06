package com.frauddetector.config;

/**
 * Configurable score bands and detection thresholds (PRD section 16). Kept in a
 * single config object so the risk classification can be tuned without editing
 * the scorers. Defaults are loaded in code and may be overridden via environment
 * variables (see {@link #fromEnv()}):
 *
 * <ul>
 *   <li>{@code RISK_LOW_MAX}      upper bound of the LOW band (default 30)</li>
 *   <li>{@code RISK_MEDIUM_MAX}   upper bound of the MEDIUM band (default 60)</li>
 *   <li>{@code RISK_HIGH_MAX}     upper bound of the HIGH band (default 80)</li>
 *   <li>{@code BILLING_ANOMALY_THRESHOLD} billing z-score that counts as a flag (default 2.0)</li>
 *   <li>{@code DUPLICATE_THRESHOLD}       similarity above which a claim is a duplicate (default 0.85)</li>
 * </ul>
 *
 * <p>Risk levels (PRD section 16): 0-30 LOW, 31-60 MEDIUM, 61-80 HIGH,
 * 81-100 CRITICAL.
 */
public final class ThresholdConfig {

    public static final String LEVEL_LOW = "LOW";
    public static final String LEVEL_MEDIUM = "MEDIUM";
    public static final String LEVEL_HIGH = "HIGH";
    public static final String LEVEL_CRITICAL = "CRITICAL";

    private final double lowMax;
    private final double mediumMax;
    private final double highMax;
    private final double billingAnomalyThreshold;
    private final double duplicateThreshold;

    public ThresholdConfig(double lowMax, double mediumMax, double highMax,
                           double billingAnomalyThreshold, double duplicateThreshold) {
        this.lowMax = lowMax;
        this.mediumMax = mediumMax;
        this.highMax = highMax;
        this.billingAnomalyThreshold = billingAnomalyThreshold;
        this.duplicateThreshold = duplicateThreshold;
    }

    /** PRD defaults: LOW<=30, MEDIUM<=60, HIGH<=80, CRITICAL above. */
    public static ThresholdConfig defaults() {
        return new ThresholdConfig(30.0, 60.0, 80.0, 2.0, 0.85);
    }

    /** Defaults overridden by any of the documented environment variables. */
    public static ThresholdConfig fromEnv() {
        ThresholdConfig d = defaults();
        return new ThresholdConfig(
                envDouble("RISK_LOW_MAX", d.lowMax),
                envDouble("RISK_MEDIUM_MAX", d.mediumMax),
                envDouble("RISK_HIGH_MAX", d.highMax),
                envDouble("BILLING_ANOMALY_THRESHOLD", d.billingAnomalyThreshold),
                envDouble("DUPLICATE_THRESHOLD", d.duplicateThreshold));
    }

    /** Classify a 0-100 final score into a risk level using the configured bands. */
    public String classify(double finalScore) {
        if (finalScore <= lowMax) {
            return LEVEL_LOW;
        }
        if (finalScore <= mediumMax) {
            return LEVEL_MEDIUM;
        }
        if (finalScore <= highMax) {
            return LEVEL_HIGH;
        }
        return LEVEL_CRITICAL;
    }

    /** True when the level is one that routes to investigation (HIGH or CRITICAL). */
    public boolean isHighRisk(String level) {
        return LEVEL_HIGH.equals(level) || LEVEL_CRITICAL.equals(level);
    }

    public double getLowMax() {
        return lowMax;
    }

    public double getMediumMax() {
        return mediumMax;
    }

    public double getHighMax() {
        return highMax;
    }

    public double getBillingAnomalyThreshold() {
        return billingAnomalyThreshold;
    }

    public double getDuplicateThreshold() {
        return duplicateThreshold;
    }

    private static double envDouble(String name, double fallback) {
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) {
            try {
                return Double.parseDouble(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }
}
