package com.frauddetector.security;

import java.time.Instant;

/**
 * An immutable audit-trail entry (PRD section 31). Each entry records who did
 * what, against which target, from where, and when: used for compliance review
 * of logins, claim submissions/views, investigation decisions, and user
 * creation. Instances are created by {@link com.frauddetector.service.AuditService}.
 */
public final class AuditLog {

    private final String id;
    private final String userId;
    private final String action;
    private final String targetId;
    private final Instant timestamp;
    private final String ip;

    public AuditLog(String id, String userId, String action, String targetId,
                    Instant timestamp, String ip) {
        this.id = id;
        this.userId = userId;
        this.action = action;
        this.targetId = targetId;
        this.timestamp = timestamp;
        this.ip = ip;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetId() {
        return targetId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getIp() {
        return ip;
    }
}
