package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Proves PRD FR-03 validation: a valid claim passes; dischargeDate before
 * admissionDate fails with a date-naming message; a negative charge fails; a
 * duplicate claimId yields 409; a missing patient reference fails. Run via
 * {@code ./build.sh test}.
 */
public final class ValidationServiceTest {

    public static void main(String[] args) {
        testValidPasses();
        testDischargeBeforeAdmissionFails();
        testNegativeChargeFails();
        testDuplicateClaimIdConflict();
        testMissingPatientFails();
        System.out.println("ValidationServiceTest OK");
    }

    private static ValidationService newService(ClaimRepository claims) {
        PatientRepository patients = new PatientRepository();
        ProviderRepository providers = new ProviderRepository();
        patients.save(new Patient("PAT-1", "Pat", 40, "F", "Austin", "INS-1"));
        providers.save(new Provider("PRV-1", "Prov", "Hosp", "Austin", "Cardio", BigDecimal.ZERO));
        return new ValidationService(patients, providers, claims);
    }

    private static SubmitClaimRequest validRequest(String claimId) {
        return new SubmitClaimRequest(
                claimId, "PAT-1", "PRV-1", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 14),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal("700"), new BigDecimal("600"));
    }

    private static void testValidPasses() {
        ValidationService svc = newService(new ClaimRepository());
        svc.validate(validRequest("CLM-1")); // should not throw
    }

    private static void testDischargeBeforeAdmissionFails() {
        ValidationService svc = newService(new ClaimRepository());
        SubmitClaimRequest req = new SubmitClaimRequest(
                "CLM-2", "PAT-1", "PRV-1", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 14),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal("700"), new BigDecimal("600"));
        ApiException ex = Assert.assertThrows(ApiException.class,
                () -> svc.validate(req), "discharge before admission fails");
        Assert.assertEquals(400, ex.getStatusCode(), "status is 400");
        Assert.assertTrue(ex.getMessage().contains("dischargeDate")
                        && ex.getMessage().contains("admissionDate"),
                "message names the date inconsistency");
    }

    private static void testNegativeChargeFails() {
        ValidationService svc = newService(new ClaimRepository());
        SubmitClaimRequest req = new SubmitClaimRequest(
                "CLM-3", "PAT-1", "PRV-1", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 14),
                new BigDecimal("-100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal("500"), new BigDecimal("600"));
        ApiException ex = Assert.assertThrows(ApiException.class,
                () -> svc.validate(req), "negative charge fails");
        Assert.assertEquals(400, ex.getStatusCode(), "status is 400");
        Assert.assertTrue(ex.getMessage().contains("medicineCost"), "message names the negative field");
    }

    private static void testDuplicateClaimIdConflict() {
        ClaimRepository claims = new ClaimRepository();
        claims.save(new Claim("CLM-DUP", "PAT-1", "PRV-1", "HOSP-A", "d", "p",
                LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 14),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "SUBMITTED", Instant.now()));
        ValidationService svc = newService(claims);
        ApiException ex = Assert.assertThrows(ApiException.class,
                () -> svc.validate(validRequest("CLM-DUP")), "duplicate claimId conflicts");
        Assert.assertEquals(409, ex.getStatusCode(), "duplicate yields 409");
    }

    private static void testMissingPatientFails() {
        ValidationService svc = newService(new ClaimRepository());
        SubmitClaimRequest req = new SubmitClaimRequest(
                "CLM-4", "PAT-UNKNOWN", "PRV-1", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 14),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal("700"), new BigDecimal("600"));
        ApiException ex = Assert.assertThrows(ApiException.class,
                () -> svc.validate(req), "missing patient fails");
        Assert.assertEquals(400, ex.getStatusCode(), "status is 400");
        Assert.assertTrue(ex.getMessage().contains("patientId"), "message names the missing patient reference");
    }
}
