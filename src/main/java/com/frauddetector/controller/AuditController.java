package com.frauddetector.controller;

import com.frauddetector.dto.AuditLogResponse;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuditLog;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Role;
import com.frauddetector.service.AuditService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Audit-trail endpoint (PRD section 31). {@code GET /api/audit} lists recent
 * entries and is restricted to {@link Role#ADMIN}.
 */
public final class AuditController {

    private final AuditService auditService;
    private final AuthFilter authFilter;

    public AuditController(AuditService auditService, AuthFilter authFilter) {
        this.auditService = auditService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        router.get("/api/audit", this::list);
    }

    private Object list(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog entry : auditService.recent()) {
            out.add(AuditLogResponse.of(entry).toJson());
        }
        ctx.respond(200, out);
        return null;
    }
}
