package com.frauddetector.domain;

import java.time.Instant;

/**
 * A manual investigation opened against a flagged claim (PRD sections 18-19,
 * Phase 3). An investigator records a {@link #getStatus() status} and, when a
 * conclusion is reached, a {@link #getDecision() decision} drawn from
 * {@link Decision} plus free-text {@code notes}. Instances are immutable;
 * {@link #withDecision} returns an updated copy.
 */
public final class Investigation {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    /** Allowed investigation decisions (PRD section 19). */
    public enum Decision {
        APPROVE,
        REJECT,
        ESCALATE,
        REQUEST_DOCUMENTS,
        MARK_SUSPICIOUS,
        FALSE_POSITIVE
    }

    private final String investigationId;
    private final String claimId;
    private final String investigatorId;
    private final String status;
    private final Decision decision;
    private final String notes;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Investigation(String investigationId, String claimId, String investigatorId,
                         String status, Decision decision, String notes,
                         Instant createdAt, Instant updatedAt) {
        this.investigationId = investigationId;
        this.claimId = claimId;
        this.investigatorId = investigatorId;
        this.status = status;
        this.decision = decision;
        this.notes = notes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Return a copy recording a decision. A terminal decision closes the
     * investigation; {@code ESCALATE}/{@code REQUEST_DOCUMENTS} keep it OPEN.
     */
    public Investigation withDecision(Decision newDecision, String newNotes, Instant when) {
        String newStatus = (newDecision == Decision.ESCALATE
                || newDecision == Decision.REQUEST_DOCUMENTS)
                ? STATUS_OPEN
                : STATUS_CLOSED;
        return new Investigation(investigationId, claimId, investigatorId, newStatus,
                newDecision, newNotes, createdAt, when);
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getInvestigatorId() {
        return investigatorId;
    }

    public String getStatus() {
        return status;
    }

    public Decision getDecision() {
        return decision;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
