package com.frauddetector.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A submitted medical claim (PRD sections 23-24). Money fields use
 * {@link BigDecimal}; dates use {@link LocalDate}. Instances are immutable;
 * {@link #withStatus(String)} returns a copy with a new status.
 */
public final class Claim {

    private final String claimId;
    private final String patientId;
    private final String providerId;
    private final String hospitalId;
    private final String diagnosis;
    private final String procedure;
    private final LocalDate admissionDate;
    private final LocalDate dischargeDate;
    private final LocalDate claimDate;
    private final BigDecimal medicineCost;
    private final BigDecimal roomCharges;
    private final BigDecimal doctorCharges;
    private final BigDecimal labCharges;
    private final BigDecimal otherCharges;
    private final BigDecimal totalBilledAmount;
    private final BigDecimal insuranceAmount;
    private final String claimStatus;
    private final Instant createdAt;

    public Claim(String claimId, String patientId, String providerId, String hospitalId,
                 String diagnosis, String procedure, LocalDate admissionDate,
                 LocalDate dischargeDate, LocalDate claimDate, BigDecimal medicineCost,
                 BigDecimal roomCharges, BigDecimal doctorCharges, BigDecimal labCharges,
                 BigDecimal otherCharges, BigDecimal totalBilledAmount, BigDecimal insuranceAmount,
                 String claimStatus, Instant createdAt) {
        this.claimId = claimId;
        this.patientId = patientId;
        this.providerId = providerId;
        this.hospitalId = hospitalId;
        this.diagnosis = diagnosis;
        this.procedure = procedure;
        this.admissionDate = admissionDate;
        this.dischargeDate = dischargeDate;
        this.claimDate = claimDate;
        this.medicineCost = medicineCost;
        this.roomCharges = roomCharges;
        this.doctorCharges = doctorCharges;
        this.labCharges = labCharges;
        this.otherCharges = otherCharges;
        this.totalBilledAmount = totalBilledAmount;
        this.insuranceAmount = insuranceAmount;
        this.claimStatus = claimStatus;
        this.createdAt = createdAt;
    }

    /** Return a copy of this claim with a different {@code claimStatus}. */
    public Claim withStatus(String newStatus) {
        return new Claim(claimId, patientId, providerId, hospitalId, diagnosis, procedure,
                admissionDate, dischargeDate, claimDate, medicineCost, roomCharges,
                doctorCharges, labCharges, otherCharges, totalBilledAmount, insuranceAmount,
                newStatus, createdAt);
    }

    public String getClaimId() {
        return claimId;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getHospitalId() {
        return hospitalId;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public String getProcedure() {
        return procedure;
    }

    public LocalDate getAdmissionDate() {
        return admissionDate;
    }

    public LocalDate getDischargeDate() {
        return dischargeDate;
    }

    public LocalDate getClaimDate() {
        return claimDate;
    }

    public BigDecimal getMedicineCost() {
        return medicineCost;
    }

    public BigDecimal getRoomCharges() {
        return roomCharges;
    }

    public BigDecimal getDoctorCharges() {
        return doctorCharges;
    }

    public BigDecimal getLabCharges() {
        return labCharges;
    }

    public BigDecimal getOtherCharges() {
        return otherCharges;
    }

    public BigDecimal getTotalBilledAmount() {
        return totalBilledAmount;
    }

    public BigDecimal getInsuranceAmount() {
        return insuranceAmount;
    }

    public String getClaimStatus() {
        return claimStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
