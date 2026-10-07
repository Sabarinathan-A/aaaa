package com.frauddetector.service;

import com.frauddetector.repository.AuditLogRepository;
import com.frauddetector.security.AuditLog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Records audit-trail entries (PRD section 31) for security-relevant actions:
 * login, claim submission, claim view, investigation decision, and user
 * creation. Entries are stored in-memory via {@link AuditLogRepository} and
 * surfaced to ADMIN callers through {@code GET /api/audit}.
 *
 * <p>Well-known action constants keep the recorded strings consistent across
 * controllers.
 */
public final class AuditService {

    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_CLAIM_SUBMIT = "CLAIM_SUBMIT";
    public static final String ACTION_CLAIM_VIEW = "CLAIM_VIEW";
    public static final String ACTION_INVESTIGATION_DECISION = "INVESTIGATION_DECISION";
    public static final String ACTION_USER_CREATE = "USER_CREATE";
    public static final String ACTION_USER_UPDATE = "USER_UPDATE";
    public static final String ACTION_CLAIM_MODIFY = "CLAIM_MODIFY";
    public static final String ACTION_CLAIM_REVIEW = "CLAIM_REVIEW";
    public static final String ACTION_INVESTIGATION_OPEN = "INVESTIGATION_OPEN";
    public static final String ACTION_EVIDENCE_UPLOAD = "EVIDENCE_UPLOAD";
    public static final String ACTION_PATIENT_VIEW = "PATIENT_VIEW";
    public static final String ACTION_MODEL_UPDATE = "MODEL_UPDATE";
    public static final String ACTION_PROVIDER_UPDATE = "PROVIDER_UPDATE";

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Record an audit entry. Null-safe for {@code ip}; stamps the current time.
     *
     * @param userId   the acting user (or null when anonymous, e.g. failed login)
     * @param action   one of the {@code ACTION_*} constants
     * @param targetId the affected resource id (claim id, user id, investigation id)
     * @param ip       the caller's remote address, or null if unknown
     */
    public AuditLog record(String userId, String action, String targetId, String ip) {
        AuditLog entry = new AuditLog(
                "AUD-" + UUID.randomUUID(),
                userId,
                action,
                targetId,
                Instant.now(),
                ip);
        return repository.save(entry);
    }

    /** Most recent audit entries, newest first (default cap of 200). */
    public List<AuditLog> recent() {
        return repository.findRecent(200);
    }

    public List<AuditLog> recent(int limit) {
        return repository.findRecent(limit);
    }
}
