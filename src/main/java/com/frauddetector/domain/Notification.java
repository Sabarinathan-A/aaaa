package com.frauddetector.domain;

import com.frauddetector.security.Role;

import java.time.Instant;

/**
 * An in-app notification record (PRD section 22). Targeted at a {@link Role}
 * (e.g. INVESTIGATOR) rather than an individual, so any user holding that role
 * sees it. No email/SMS is sent: external messaging is a documented future hook
 * (the sandbox is network-isolated). Instances are immutable;
 * {@link #asRead(Instant)} returns a read copy.
 */
public final class Notification {

    private final String id;
    private final Role targetRole;
    private final String type;
    private final String message;
    private final String claimId;
    private final String severity;
    private final boolean read;
    private final Instant createdAt;

    public Notification(String id, Role targetRole, String type, String message,
                        String claimId, String severity, boolean read, Instant createdAt) {
        this.id = id;
        this.targetRole = targetRole;
        this.type = type;
        this.message = message;
        this.claimId = claimId;
        this.severity = severity;
        this.read = read;
        this.createdAt = createdAt;
    }

    /** Return a copy of this notification marked read. */
    public Notification asRead(Instant when) {
        return new Notification(id, targetRole, type, message, claimId, severity, true,
                createdAt);
    }

    public String getId() {
        return id;
    }

    public Role getTargetRole() {
        return targetRole;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getSeverity() {
        return severity;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
