package com.frauddetector.dto;

import com.frauddetector.domain.Claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Response view of a persisted {@link Claim}, serialized via the Json codec. */
public final class ClaimResponse {

    private final Claim claim;

    private ClaimResponse(Claim claim) {
        this.claim = claim;
    }

    public static ClaimResponse of(Claim claim) {
        return new ClaimResponse(claim);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("claimId", claim.getClaimId());
        json.put("patientId", claim.getPatientId());
        json.put("providerId", claim.getProviderId());
        json.put("hospitalId", claim.getHospitalId());
        json.put("diagnosis", claim.getDiagnosis());
        json.put("procedure", claim.getProcedure());
        json.put("admissionDate", asText(claim.getAdmissionDate()));
        json.put("dischargeDate", asText(claim.getDischargeDate()));
        json.put("claimDate", asText(claim.getClaimDate()));
        json.put("medicineCost", asText(claim.getMedicineCost()));
        json.put("roomCharges", asText(claim.getRoomCharges()));
        json.put("doctorCharges", asText(claim.getDoctorCharges()));
        json.put("labCharges", asText(claim.getLabCharges()));
        json.put("otherCharges", asText(claim.getOtherCharges()));
        json.put("totalBilledAmount", asText(claim.getTotalBilledAmount()));
        json.put("insuranceAmount", asText(claim.getInsuranceAmount()));
        json.put("claimStatus", claim.getClaimStatus());
        json.put("createdAt", claim.getCreatedAt() == null ? null : claim.getCreatedAt().toString());
        return json;
    }

    private static String asText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String asText(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
