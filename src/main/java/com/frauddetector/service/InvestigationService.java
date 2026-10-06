package com.frauddetector.service;

import com.frauddetector.domain.Investigation;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.security.Principal;

import java.time.Instant;
import java.util.UUID;

/**
 * Manual investigation workflow (PRD sections 18-19, Phase 3). Opens an
 * investigation against a claim and records an investigator's decision. The
 * controller enforces that only INVESTIGATOR/ADMIN may reach these methods.
 */
public final class InvestigationService {

    private final InvestigationRepository investigations;
    private final ClaimRepository claims;

    public InvestigationService(InvestigationRepository investigations, ClaimRepository claims) {
        this.investigations = investigations;
        this.claims = claims;
    }

    /** Open an investigation for a claim; 404 if the claim does not exist. */
    public Investigation open(String claimId, String notes, Principal principal) {
        if (claimId == null || claimId.isBlank()) {
            throw new ApiException(400, "claimId is required");
        }
        if (!claims.existsById(claimId)) {
            throw new ApiException(404, "Claim '" + claimId + "' not found");
        }
        Instant now = Instant.now();
        Investigation investigation = new Investigation(
                "INV-" + UUID.randomUUID(),
                claimId,
                principal == null ? null : principal.userId(),
                Investigation.STATUS_OPEN,
                null,
                notes,
                now,
                now);
        return investigations.save(investigation);
    }

    /** Record a decision + notes on an existing investigation; 404 if unknown. */
    public Investigation decide(String investigationId, String decisionRaw, String notes) {
        Investigation existing = investigations.findById(investigationId)
                .orElseThrow(() -> new ApiException(404,
                        "Investigation '" + investigationId + "' not found"));
        Investigation.Decision decision = parseDecision(decisionRaw);
        Investigation updated = existing.withDecision(decision, notes, Instant.now());
        return investigations.save(updated);
    }

    public Investigation get(String investigationId) {
        return investigations.findById(investigationId)
                .orElseThrow(() -> new ApiException(404,
                        "Investigation '" + investigationId + "' not found"));
    }

    private static Investigation.Decision parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "decision is required");
        }
        try {
            return Investigation.Decision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Invalid decision '" + raw
                    + "'. Allowed: APPROVE, REJECT, ESCALATE, REQUEST_DOCUMENTS, MARK_SUSPICIOUS, FALSE_POSITIVE");
        }
    }
}
