package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.dto.ClaimResponse;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.security.Principal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claim lifecycle service: validate + persist on submission, run the fraud
 * engine, store the analysis, and read it back.
 */
public final class ClaimService {

    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private final ClaimRepository claims;
    private final ValidationService validation;
    private final FraudRiskService fraudRiskService;
    private final FraudAnalysisRepository analyses;

    public ClaimService(ClaimRepository claims, ValidationService validation,
                        FraudRiskService fraudRiskService, FraudAnalysisRepository analyses) {
        this.claims = claims;
        this.validation = validation;
        this.fraudRiskService = fraudRiskService;
        this.analyses = analyses;
    }

    /**
     * Validate, mark SUBMITTED, persist, run the fraud engine, store the
     * analysis, and return the created claim with its analysis attached.
     */
    public ClaimResponse submitClaim(SubmitClaimRequest req, Principal principal) {
        validation.validate(req);
        Claim claim = new Claim(
                req.claimId, req.patientId, req.providerId, req.hospitalId,
                req.diagnosis, req.procedure, req.admissionDate, req.dischargeDate, req.claimDate,
                req.medicineCost, req.roomCharges, req.doctorCharges, req.labCharges, req.otherCharges,
                req.totalBilledAmount, req.insuranceAmount, STATUS_SUBMITTED, Instant.now());
        claims.save(claim);

        // Scoring runs after persistence so the claim is part of its own
        // historical context handling (the scorers exclude it where appropriate).
        FraudAnalysis analysis = fraudRiskService.analyze(claim);
        analyses.save(analysis);
        return ClaimResponse.of(claim, analysis);
    }

    /** Fetch a claim by id (with its latest analysis, if any) or raise 404. */
    public ClaimResponse getClaim(String claimId) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> new ApiException(404, "Claim '" + claimId + "' not found"));
        FraudAnalysis analysis = analyses.findByClaimId(claimId).orElse(null);
        return ClaimResponse.of(claim, analysis);
    }

    /** The stored analysis for a claim, or 404 if the claim/analysis is missing. */
    public FraudAnalysis getAnalysis(String claimId) {
        if (!claims.existsById(claimId)) {
            throw new ApiException(404, "Claim '" + claimId + "' not found");
        }
        return analyses.findByClaimId(claimId)
                .orElseThrow(() -> new ApiException(404,
                        "No fraud analysis found for claim '" + claimId + "'"));
    }

    /**
     * List claims. Filtering is a stub for now (returns all); full search is
     * added in FEAT-004.
     */
    public List<Map<String, Object>> listClaims(Map<String, String> filters) {
        return claims.findAll().stream()
                .map(c -> ClaimResponse.of(c, analyses.findByClaimId(c.getClaimId()).orElse(null)).toJson())
                .collect(Collectors.toList());
    }

    /** Claims whose latest analysis is HIGH or CRITICAL (PRD section 16). */
    public List<Map<String, Object>> listHighRisk() {
        return claims.findAll().stream()
                .map(c -> new Object[]{c, analyses.findByClaimId(c.getClaimId()).orElse(null)})
                .filter(pair -> {
                    FraudAnalysis a = (FraudAnalysis) pair[1];
                    return a != null && fraudRiskService.thresholds().isHighRisk(a.getRiskLevel());
                })
                .map(pair -> ClaimResponse.of((Claim) pair[0], (FraudAnalysis) pair[1]).toJson())
                .collect(Collectors.toList());
    }
}
