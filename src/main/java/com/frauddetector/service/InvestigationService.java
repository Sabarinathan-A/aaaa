package com.frauddetector.service;

import com.frauddetector.domain.Investigation;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.security.Principal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manual investigation workflow (PRD sections 18-19, Phase 3). Opens an
 * investigation against a claim, records an investigator's decision, and keeps
 * the claim's {@code claimStatus} in sync with the investigation outcome. The
 * controller enforces that only INVESTIGATOR/ADMIN may open or decide.
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
        investigations.save(investigation);
        updateClaimStatus(claimId, ClaimService.STATUS_UNDER_INVESTIGATION);
        return investigation;
    }

    /**
     * Record a decision + notes on an existing investigation; 404 if unknown.
     * The claim status follows the decision (see {@link #claimStatusFor}).
     */
    public Investigation decide(String investigationId, String decisionRaw, String notes) {
        Investigation existing = get(investigationId);
        Investigation.Decision decision = parseDecision(decisionRaw);
        Investigation updated = existing.withDecision(decision, notes, Instant.now());
        investigations.save(updated);
        updateClaimStatus(updated.getClaimId(), claimStatusFor(decision));
        return updated;
    }

    public Investigation get(String investigationId) {
        return investigations.findById(investigationId)
                .orElseThrow(() -> new ApiException(404,
                        "Investigation '" + investigationId + "' not found"));
    }

    /**
     * List investigations newest-first, optionally filtered by {@code status}
     * (OPEN|CLOSED), {@code claimId}, {@code investigatorId} or {@code decision}.
     */
    public List<Investigation> list(Map<String, String> filters) {
        Map<String, String> f = filters == null ? Map.of() : filters;
        List<Investigation> out = new ArrayList<>();
        for (Investigation i : investigations.findAll()) {
            if (!matches(f.get("status"), i.getStatus())) {
                continue;
            }
            if (!matches(f.get("claimId"), i.getClaimId())) {
                continue;
            }
            if (!matches(f.get("investigatorId"), i.getInvestigatorId())) {
                continue;
            }
            if (!matches(f.get("decision"), i.getDecision() == null ? null : i.getDecision().name())) {
                continue;
            }
            out.add(i);
        }
        out.sort(Comparator.comparing(Investigation::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    /** Claim status implied by an investigation decision. */
    public static String claimStatusFor(Investigation.Decision decision) {
        switch (decision) {
            case APPROVE:
            case FALSE_POSITIVE:
                return ClaimService.STATUS_APPROVED;
            case REJECT:
                return ClaimService.STATUS_REJECTED;
            case ESCALATE:
                return ClaimService.STATUS_ESCALATED;
            case REQUEST_DOCUMENTS:
                return ClaimService.STATUS_DOCUMENTS_REQUESTED;
            case MARK_SUSPICIOUS:
                return ClaimService.STATUS_SUSPICIOUS;
            default:
                return ClaimService.STATUS_UNDER_INVESTIGATION;
        }
    }

    private void updateClaimStatus(String claimId, String status) {
        claims.findById(claimId).ifPresent(c -> claims.save(c.withStatus(status)));
    }

    private static boolean matches(String filter, String actual) {
        return filter == null || filter.isBlank() || filter.trim().equalsIgnoreCase(actual);
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
