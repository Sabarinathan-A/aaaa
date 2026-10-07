package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Notification;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.NotificationRepository;
import com.frauddetector.security.Role;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-app notification service (PRD section 22). When a submitted claim is
 * classified CRITICAL (and, by default, HIGH), an investigator-targeted
 * notification record is created so the investigation queue is alerted.
 *
 * <p>There is intentionally no email/SMS delivery: the sandbox is
 * network-isolated (INTEGRATIONS_ONLY) and no SMTP endpoint is reachable.
 * External delivery is a documented future hook; {@link #onClaimScored} is the
 * single integration point a real notifier would extend.
 */
public final class NotificationService {

    public static final String TYPE_HIGH_RISK_CLAIM = "HIGH_RISK_CLAIM";

    private final NotificationRepository repository;
    private final boolean notifyOnHigh;
    private final List<ExternalNotifier> externalNotifiers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public NotificationService(NotificationRepository repository) {
        this(repository, true);
    }

    public NotificationService(NotificationRepository repository, boolean notifyOnHigh) {
        this.repository = repository;
        this.notifyOnHigh = notifyOnHigh;
    }

    /**
     * Hook invoked after a claim is scored. Creates an INVESTIGATOR notification
     * for CRITICAL claims (and HIGH when {@code notifyOnHigh}). Returns the
     * created notification, or null when no notification was warranted.
     */
    public Notification onClaimScored(Claim claim, FraudAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        String level = analysis.getRiskLevel();
        boolean critical = "CRITICAL".equals(level);
        boolean high = "HIGH".equals(level);
        if (!critical && !(high && notifyOnHigh)) {
            return null;
        }
        String message = String.format(
                "Claim %s was classified %s (risk score %.1f) and needs investigator review.",
                claim.getClaimId(), level, analysis.getFinalRiskScore());
        return notify(Role.INVESTIGATOR, TYPE_HIGH_RISK_CLAIM, message, claim.getClaimId(), level);
    }

    /**
     * Create an in-app notification for {@code role} and hand it to every
     * registered external notifier (email outbox, webhook). External delivery
     * failures never block the in-app record.
     */
    public Notification notify(Role role, String type, String message, String claimId, String severity) {
        Notification notification = new Notification(
                "NOT-" + UUID.randomUUID(), role, type, message, claimId, severity, false, Instant.now());
        repository.save(notification);
        for (ExternalNotifier notifier : externalNotifiers) {
            try {
                notifier.deliver(notification);
            } catch (RuntimeException e) {
                System.err.println("WARN: external notifier " + notifier.name() + " failed: " + e.getMessage());
            }
        }
        return notification;
    }

    /** Register an external delivery channel (see {@link ExternalNotifier}). */
    public void addExternalNotifier(ExternalNotifier notifier) {
        if (notifier != null) {
            externalNotifiers.add(notifier);
        }
    }

    /** Pluggable out-of-app delivery channel for notifications. */
    public interface ExternalNotifier {
        String name();

        void deliver(Notification notification);
    }

    /** Notifications targeted at the caller's role, newest-first. */
    public List<Notification> forRole(Role role) {
        return repository.findByRole(role);
    }

    /** Mark a notification read; 404 if unknown. */
    public Notification markRead(String id) {
        Notification existing = repository.findById(id)
                .orElseThrow(() -> new ApiException(404, "Notification '" + id + "' not found"));
        Notification updated = existing.asRead(Instant.now());
        return repository.save(updated);
    }
}
