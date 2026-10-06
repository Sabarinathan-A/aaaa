package com.frauddetector.dto;

import com.frauddetector.http.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Request body for {@code POST /api/claims}. Fields are parsed from JSON-friendly
 * types: numbers (or numeric strings) into {@link BigDecimal}, and ISO date
 * strings (yyyy-MM-dd) into {@link LocalDate}. Parsing errors raise 400; the
 * {@code ValidationService} enforces the business rules.
 */
public final class SubmitClaimRequest {

    public final String claimId;
    public final String patientId;
    public final String providerId;
    public final String hospitalId;
    public final String diagnosis;
    public final String procedure;
    public final LocalDate admissionDate;
    public final LocalDate dischargeDate;
    public final LocalDate claimDate;
    public final BigDecimal medicineCost;
    public final BigDecimal roomCharges;
    public final BigDecimal doctorCharges;
    public final BigDecimal labCharges;
    public final BigDecimal otherCharges;
    public final BigDecimal totalBilledAmount;
    public final BigDecimal insuranceAmount;

    public SubmitClaimRequest(String claimId, String patientId, String providerId, String hospitalId,
                              String diagnosis, String procedure, LocalDate admissionDate,
                              LocalDate dischargeDate, LocalDate claimDate, BigDecimal medicineCost,
                              BigDecimal roomCharges, BigDecimal doctorCharges, BigDecimal labCharges,
                              BigDecimal otherCharges, BigDecimal totalBilledAmount, BigDecimal insuranceAmount) {
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
    }

    public static SubmitClaimRequest fromJson(Map<String, Object> json) {
        return new SubmitClaimRequest(
                str(json, "claimId"),
                str(json, "patientId"),
                str(json, "providerId"),
                str(json, "hospitalId"),
                str(json, "diagnosis"),
                str(json, "procedure"),
                date(json, "admissionDate"),
                date(json, "dischargeDate"),
                date(json, "claimDate"),
                money(json, "medicineCost"),
                money(json, "roomCharges"),
                money(json, "doctorCharges"),
                money(json, "labCharges"),
                money(json, "otherCharges"),
                money(json, "totalBilledAmount"),
                money(json, "insuranceAmount"));
    }

    private static String str(Map<String, Object> json, String key) {
        Object v = json.get(key);
        return v == null ? null : v.toString();
    }

    private static BigDecimal money(Map<String, Object> json, String key) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof Number) {
                return new BigDecimal(v.toString());
            }
            String s = v.toString().trim();
            if (s.isEmpty()) {
                return null;
            }
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Field '" + key + "' is not a valid number: " + v);
        }
    }

    private static LocalDate date(Map<String, Object> json, String key) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "Field '" + key + "' is not a valid ISO date (yyyy-MM-dd): " + v);
        }
    }
}
