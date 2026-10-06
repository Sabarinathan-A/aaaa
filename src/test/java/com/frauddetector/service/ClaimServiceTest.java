package com.frauddetector.service;

import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Proves submitClaim persists with status SUBMITTED and getClaim returns the
 * stored claim, plus 404 for an unknown id. Run via {@code ./build.sh test}.
 */
public final class ClaimServiceTest {

    public static void main(String[] args) {
        testSubmitThenGet();
        testUnknownIdNotFound();
        System.out.println("ClaimServiceTest OK");
    }

    private static ClaimService newService(ClaimRepository claims) {
        PatientRepository patients = new PatientRepository();
        ProviderRepository providers = new ProviderRepository();
        patients.save(new Patient("PAT-1", "Pat", 40, "F", "Austin", "INS-1"));
        providers.save(new Provider("PRV-1", "Prov", "Hosp", "Austin", "Cardio", BigDecimal.ZERO));
        ValidationService validation = new ValidationService(patients, providers, claims);
        return new ClaimService(claims, validation);
    }

    private static void testSubmitThenGet() {
        ClaimService svc = newService(new ClaimRepository());
        SubmitClaimRequest req = new SubmitClaimRequest(
                "CLM-100", "PAT-1", "PRV-1", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 14),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"),
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal("700"), new BigDecimal("600"));
        Principal principal = new Principal("U-OFFICER", Role.CLAIM_OFFICER);

        Map<String, Object> submitted = svc.submitClaim(req, principal).toJson();
        Assert.assertEquals("CLM-100", submitted.get("claimId"), "submitted claimId");
        Assert.assertEquals("SUBMITTED", submitted.get("claimStatus"), "status is SUBMITTED");

        Map<String, Object> fetched = svc.getClaim("CLM-100").toJson();
        Assert.assertEquals("CLM-100", fetched.get("claimId"), "fetched claimId matches");
        Assert.assertEquals("PAT-1", fetched.get("patientId"), "fetched patientId matches");
        Assert.assertEquals("700", fetched.get("totalBilledAmount"), "fetched total matches");
    }

    private static void testUnknownIdNotFound() {
        ClaimService svc = newService(new ClaimRepository());
        ApiException ex = Assert.assertThrows(ApiException.class,
                () -> svc.getClaim("DOES-NOT-EXIST"), "unknown id returns 404");
        Assert.assertEquals(404, ex.getStatusCode(), "status is 404");
    }
}
