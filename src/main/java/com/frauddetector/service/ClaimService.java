package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.dto.ClaimResponse;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.security.Principal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claim lifecycle service: validate + persist on submission, and read back.
 */
public final class ClaimService {

    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private final ClaimRepository claims;
    private final ValidationService validation;

    public ClaimService(ClaimRepository claims, ValidationService validation) {
        this.claims = claims;
        this.validation = validation;
    }

    /** Validate, mark SUBMITTED, persist, and return the created claim. */
    public ClaimResponse submitClaim(SubmitClaimRequest req, Principal principal) {
        validation.validate(req);
        Claim claim = new Claim(
                req.claimId, req.patientId, req.providerId, req.hospitalId,
                req.diagnosis, req.procedure, req.admissionDate, req.dischargeDate, req.claimDate,
                req.medicineCost, req.roomCharges, req.doctorCharges, req.labCharges, req.otherCharges,
                req.totalBilledAmount, req.insuranceAmount, STATUS_SUBMITTED, Instant.now());
        claims.save(claim);
        return ClaimResponse.of(claim);
    }

    /** Fetch a claim by id or raise 404. */
    public ClaimResponse getClaim(String claimId) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> new ApiException(404, "Claim '" + claimId + "' not found"));
        return ClaimResponse.of(claim);
    }

    /**
     * List claims. Filtering is a stub for now (returns all); full search is
     * added in FEAT-004.
     */
    public List<Map<String, Object>> listClaims(Map<String, String> filters) {
        return claims.findAll().stream()
                .map(c -> ClaimResponse.of(c).toJson())
                .collect(Collectors.toList());
    }
}
