package com.frauddetector.domain;

import java.math.BigDecimal;

/**
 * A healthcare provider referenced by claims (PRD sections 23-24). The
 * {@code riskScore} is a running indicator refined by later ML features.
 */
public final class Provider {

    private final String providerId;
    private final String providerName;
    private final String hospital;
    private final String location;
    private final String specialization;
    private final BigDecimal riskScore;

    public Provider(String providerId, String providerName, String hospital,
                    String location, String specialization, BigDecimal riskScore) {
        this.providerId = providerId;
        this.providerName = providerName;
        this.hospital = hospital;
        this.location = location;
        this.specialization = specialization;
        this.riskScore = riskScore;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getHospital() {
        return hospital;
    }

    public String getLocation() {
        return location;
    }

    public String getSpecialization() {
        return specialization;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }
}
