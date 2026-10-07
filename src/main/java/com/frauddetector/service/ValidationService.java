package com.frauddetector.service;

import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates an incoming claim against PRD FR-03 rules. All violations are
 * collected and reported together.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Required fields present: claimId, patientId, providerId, diagnosis,
 *       admissionDate, dischargeDate, claimDate, totalBilledAmount.</li>
 *   <li>Each numeric charge component and the total/insurance amounts must be
 *       non-negative.</li>
 *   <li><b>Total consistency rule (chosen):</b> {@code totalBilledAmount} must
 *       equal the arithmetic sum of the five component charges (medicineCost +
 *       roomCharges + doctorCharges + labCharges + otherCharges), compared by
 *       {@link BigDecimal#compareTo}. Any mismatch is a violation.</li>
 *   <li>{@code dischargeDate} must not be before {@code admissionDate}.</li>
 *   <li>Referenced {@code patientId} and {@code providerId} must exist.</li>
 *   <li>{@code claimId} must be unique (not already stored).</li>
 * </ul>
 *
 * <p>A duplicate claimId raises {@link ApiException} 409. Any other set of
 * violations raises {@link ApiException} 400 with a message listing them.
 */
public final class ValidationService {

    private final PatientRepository patients;
    private final ProviderRepository providers;
    private final ClaimRepository claims;

    public ValidationService(PatientRepository patients, ProviderRepository providers, ClaimRepository claims) {
        this.patients = patients;
        this.providers = providers;
        this.claims = claims;
    }

    /** Throws {@link ApiException} (409 for duplicate id, else 400) if invalid. */
    public void validate(SubmitClaimRequest req) {
        validate(req, false);
    }

    /**
     * Validate a new submission ({@code isUpdate=false}) or a correction of an
     * existing claim ({@code isUpdate=true}, which skips the duplicate-id rule).
     */
    public void validate(SubmitClaimRequest req, boolean isUpdate) {
        // Duplicate claimId -> 409, checked first and independently.
        if (!isUpdate && isPresent(req.claimId) && claims.existsById(req.claimId)) {
            throw new ApiException(409, "A claim with claimId '" + req.claimId + "' already exists");
        }

        List<String> violations = new ArrayList<>();

        requirePresent(violations, "claimId", req.claimId);
        requirePresent(violations, "patientId", req.patientId);
        requirePresent(violations, "providerId", req.providerId);
        requirePresent(violations, "diagnosis", req.diagnosis);
        requireDate(violations, "admissionDate", req.admissionDate);
        requireDate(violations, "dischargeDate", req.dischargeDate);
        requireDate(violations, "claimDate", req.claimDate);
        requireAmount(violations, "totalBilledAmount", req.totalBilledAmount);

        nonNegative(violations, "medicineCost", req.medicineCost);
        nonNegative(violations, "roomCharges", req.roomCharges);
        nonNegative(violations, "doctorCharges", req.doctorCharges);
        nonNegative(violations, "labCharges", req.labCharges);
        nonNegative(violations, "otherCharges", req.otherCharges);
        nonNegative(violations, "totalBilledAmount", req.totalBilledAmount);
        nonNegative(violations, "insuranceAmount", req.insuranceAmount);

        // Date inconsistency: dischargeDate before admissionDate.
        if (req.admissionDate != null && req.dischargeDate != null
                && req.dischargeDate.isBefore(req.admissionDate)) {
            violations.add("dischargeDate (" + req.dischargeDate
                    + ") must not be before admissionDate (" + req.admissionDate + ")");
        }

        // Total consistency: total must equal the sum of components.
        if (req.totalBilledAmount != null) {
            BigDecimal sum = sumComponents(req);
            if (req.totalBilledAmount.compareTo(sum) != 0) {
                violations.add("totalBilledAmount (" + req.totalBilledAmount.toPlainString()
                        + ") does not equal the sum of component charges (" + sum.toPlainString() + ")");
            }
        }

        // Referenced entities must exist.
        if (isPresent(req.patientId) && !patients.existsById(req.patientId)) {
            violations.add("referenced patientId '" + req.patientId + "' does not exist");
        }
        if (isPresent(req.providerId) && !providers.existsById(req.providerId)) {
            violations.add("referenced providerId '" + req.providerId + "' does not exist");
        }

        if (!violations.isEmpty()) {
            throw new ApiException(400, "Claim validation failed: " + String.join("; ", violations));
        }
    }

    private static BigDecimal sumComponents(SubmitClaimRequest req) {
        return orZero(req.medicineCost)
                .add(orZero(req.roomCharges))
                .add(orZero(req.doctorCharges))
                .add(orZero(req.labCharges))
                .add(orZero(req.otherCharges));
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void requirePresent(List<String> violations, String field, String value) {
        if (!isPresent(value)) {
            violations.add("required field '" + field + "' is missing");
        }
    }

    private static void requireDate(List<String> violations, String field, Object value) {
        if (value == null) {
            violations.add("required field '" + field + "' is missing");
        }
    }

    private static void requireAmount(List<String> violations, String field, BigDecimal value) {
        if (value == null) {
            violations.add("required field '" + field + "' is missing");
        }
    }

    private static void nonNegative(List<String> violations, String field, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            violations.add("field '" + field + "' must not be negative (was " + value.toPlainString() + ")");
        }
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
