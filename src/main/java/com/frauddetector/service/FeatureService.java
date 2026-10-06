package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.Patient;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature extraction (PRD section 26). Turns a {@link Claim} plus its historical
 * context (the claims already held in the repositories) into a numeric feature
 * vector consumed by the ML scorers. Every feature is documented so the model
 * stays transparent and explainable.
 *
 * <p>Extracted features ({@link FeatureVector}):
 * <ul>
 *   <li>{@code patientAge} - the patient's age (years); 0 if unknown.</li>
 *   <li>{@code treatmentDurationDays} - discharge minus admission, in days.</li>
 *   <li>{@code totalAmount} - the claim's total billed amount.</li>
 *   <li>{@code medicineRatio/roomRatio/doctorRatio/labRatio/otherRatio} -
 *       each component cost divided by the total (how the bill is composed).</li>
 *   <li>{@code patientClaimFrequency} - number of prior claims for this patient.</li>
 *   <li>{@code patientAvgHistoricalAmount} - mean total of the patient's prior claims.</li>
 *   <li>{@code providerAvgHistoricalAmount} - mean total of the provider's prior claims.</li>
 *   <li>{@code providerAmountDeviation} - (total - providerAvg) / providerAvg; how far
 *       above/below the provider's typical bill this claim sits.</li>
 *   <li>{@code procedureAmountDeviation} - same ratio but against the historical mean for
 *       the same procedure across all providers.</li>
 *   <li>{@code duplicateSimilarity} - highest similarity to any existing claim [0,1]
 *       (populated by the caller via {@link FeatureVector#duplicateSimilarity}).</li>
 * </ul>
 */
public final class FeatureService {

    private final ClaimRepository claims;
    private final PatientRepository patients;

    public FeatureService(ClaimRepository claims, PatientRepository patients) {
        this.claims = claims;
        this.patients = patients;
    }

    /**
     * Immutable, named feature vector. Field names mirror the documentation on
     * {@link FeatureService} and the keys returned by {@link #asMap()}.
     */
    public static final class FeatureVector {
        public double patientAge;
        public double treatmentDurationDays;
        public double totalAmount;
        public double medicineRatio;
        public double roomRatio;
        public double doctorRatio;
        public double labRatio;
        public double otherRatio;
        public double patientClaimFrequency;
        public double patientAvgHistoricalAmount;
        public double providerAvgHistoricalAmount;
        public double providerAmountDeviation;
        public double procedureAmountDeviation;
        public double duplicateSimilarity;

        /** A stable, ordered view used for logging and the anomaly detector. */
        public Map<String, Double> asMap() {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("patientAge", patientAge);
            m.put("treatmentDurationDays", treatmentDurationDays);
            m.put("totalAmount", totalAmount);
            m.put("medicineRatio", medicineRatio);
            m.put("roomRatio", roomRatio);
            m.put("doctorRatio", doctorRatio);
            m.put("labRatio", labRatio);
            m.put("otherRatio", otherRatio);
            m.put("patientClaimFrequency", patientClaimFrequency);
            m.put("patientAvgHistoricalAmount", patientAvgHistoricalAmount);
            m.put("providerAvgHistoricalAmount", providerAvgHistoricalAmount);
            m.put("providerAmountDeviation", providerAmountDeviation);
            m.put("procedureAmountDeviation", procedureAmountDeviation);
            m.put("duplicateSimilarity", duplicateSimilarity);
            return m;
        }
    }

    /**
     * Build the feature vector for {@code claim}. {@code excludeSelf} controls
     * whether a claim with the same id already stored is removed from the
     * historical context (true when scoring an already-persisted claim).
     */
    public FeatureVector extract(Claim claim, boolean excludeSelf) {
        FeatureVector f = new FeatureVector();

        Patient patient = patients.findById(claim.getPatientId()).orElse(null);
        f.patientAge = patient == null ? 0.0 : patient.getAge();

        f.treatmentDurationDays = durationDays(claim);

        double total = toDouble(claim.getTotalBilledAmount());
        f.totalAmount = total;
        double safeTotal = total == 0.0 ? 1.0 : total;
        f.medicineRatio = toDouble(claim.getMedicineCost()) / safeTotal;
        f.roomRatio = toDouble(claim.getRoomCharges()) / safeTotal;
        f.doctorRatio = toDouble(claim.getDoctorCharges()) / safeTotal;
        f.labRatio = toDouble(claim.getLabCharges()) / safeTotal;
        f.otherRatio = toDouble(claim.getOtherCharges()) / safeTotal;

        List<Claim> patientHistory = exclude(claims.findByPatientId(claim.getPatientId()), claim, excludeSelf);
        f.patientClaimFrequency = patientHistory.size();
        f.patientAvgHistoricalAmount = meanTotal(patientHistory);

        List<Claim> providerHistory = exclude(claims.findByProviderId(claim.getProviderId()), claim, excludeSelf);
        double providerAvg = meanTotal(providerHistory);
        f.providerAvgHistoricalAmount = providerAvg;
        f.providerAmountDeviation = providerAvg == 0.0 ? 0.0 : (total - providerAvg) / providerAvg;

        double procedureAvg = meanTotal(exclude(sameProcedure(claim), claim, excludeSelf));
        f.procedureAmountDeviation = procedureAvg == 0.0 ? 0.0 : (total - procedureAvg) / procedureAvg;

        // duplicateSimilarity is filled in by the DuplicateDetector; left 0 here.
        f.duplicateSimilarity = 0.0;
        return f;
    }

    private List<Claim> sameProcedure(Claim claim) {
        return claims.findAll().stream()
                .filter(c -> c.getProcedure() != null
                        && c.getProcedure().equalsIgnoreCase(claim.getProcedure()))
                .toList();
    }

    private static List<Claim> exclude(List<Claim> history, Claim claim, boolean excludeSelf) {
        if (!excludeSelf) {
            return history;
        }
        return history.stream()
                .filter(c -> !c.getClaimId().equals(claim.getClaimId()))
                .toList();
    }

    private static double meanTotal(List<Claim> history) {
        if (history.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Claim c : history) {
            sum += toDouble(c.getTotalBilledAmount());
        }
        return sum / history.size();
    }

    private static double durationDays(Claim claim) {
        if (claim.getAdmissionDate() == null || claim.getDischargeDate() == null) {
            return 0.0;
        }
        return ChronoUnit.DAYS.between(claim.getAdmissionDate(), claim.getDischargeDate());
    }

    static double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
