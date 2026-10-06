package com.frauddetector.dto;

import com.frauddetector.domain.Notification;

import java.util.LinkedHashMap;
import java.util.Map;

/** Response view of a {@link Notification}, serialized via the Json codec. */
public final class NotificationResponse {

    private final Notification notification;

    private NotificationResponse(Notification notification) {
        this.notification = notification;
    }

    public static NotificationResponse of(Notification notification) {
        return new NotificationResponse(notification);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", notification.getId());
        json.put("targetRole", notification.getTargetRole() == null ? null : notification.getTargetRole().name());
        json.put("type", notification.getType());
        json.put("message", notification.getMessage());
        json.put("claimId", notification.getClaimId());
        json.put("severity", notification.getSeverity());
        json.put("read", notification.isRead());
        json.put("createdAt", notification.getCreatedAt() == null ? null : notification.getCreatedAt().toString());
        return json;
    }
}
