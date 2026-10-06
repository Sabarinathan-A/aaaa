package com.frauddetector.controller;

import com.frauddetector.domain.Notification;
import com.frauddetector.dto.NotificationResponse;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.service.NotificationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-app notification endpoints (PRD section 22):
 * <ul>
 *   <li>{@code GET /api/notifications} - notifications targeted at the caller's
 *       role; {@code ?unread=true} returns only unread entries</li>
 *   <li>{@code POST /api/notifications/{id}/read} - mark one read</li>
 * </ul>
 * Any authenticated role may read its own role's notifications.
 */
public final class NotificationController {

    private final NotificationService notificationService;
    private final AuthFilter authFilter;

    public NotificationController(NotificationService notificationService, AuthFilter authFilter) {
        this.notificationService = notificationService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        router.get("/api/notifications", this::list);
        router.post("/api/notifications/{id}/read", this::markRead);
    }

    private Object list(HttpContext ctx) {
        Principal principal = authFilter.authenticate(ctx);
        boolean unreadOnly = "true".equalsIgnoreCase(ctx.queryParam("unread"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Notification n : notificationService.forRole(principal.role())) {
            if (unreadOnly && n.isRead()) {
                continue;
            }
            out.add(NotificationResponse.of(n).toJson());
        }
        ctx.respond(200, out);
        return null;
    }

    private Object markRead(HttpContext ctx) {
        authFilter.authenticate(ctx);
        Notification updated = notificationService.markRead(ctx.pathParam("id"));
        ctx.respond(200, NotificationResponse.of(updated).toJson());
        return null;
    }
}
