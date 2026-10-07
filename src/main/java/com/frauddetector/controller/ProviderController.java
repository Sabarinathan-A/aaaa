package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.AuditService;
import com.frauddetector.service.ProviderService;

import java.util.Map;

/**
 * Provider endpoints (PRD sections 5.1, 13):
 * <ul>
 *   <li>{@code GET /api/providers} - all providers with recomputed risk score (any authenticated role)</li>
 *   <li>{@code GET /api/providers/{id}} - a single provider with risk score (any authenticated role)</li>
 *   <li>{@code POST /api/providers} - register a provider (ADMIN)</li>
 *   <li>{@code PUT /api/providers/{id}} - edit provider details (ADMIN)</li>
 * </ul>
 */
public final class ProviderController {

    private final ProviderService providerService;
    private final AuthFilter authFilter;
    private final AuditService auditService;

    public ProviderController(ProviderService providerService, AuthFilter authFilter) {
        this(providerService, authFilter, null);
    }

    public ProviderController(ProviderService providerService, AuthFilter authFilter, AuditService auditService) {
        this.providerService = providerService;
        this.authFilter = authFilter;
        this.auditService = auditService;
    }

    public void register(Router router) {
        router.get("/api/providers", this::list);
        router.post("/api/providers", this::create);
        router.get("/api/providers/{id}", this::getOne);
        router.put("/api/providers/{id}", this::update);
    }

    private Object list(HttpContext ctx) {
        authFilter.authenticate(ctx);
        ctx.respond(200, providerService.listProviders());
        return null;
    }

    private Object getOne(HttpContext ctx) {
        authFilter.authenticate(ctx);
        ctx.respond(200, providerService.getProvider(ctx.pathParam("id")));
        return null;
    }

    private Object create(HttpContext ctx) {
        Principal p = authFilter.requireRole(ctx, Role.ADMIN);
        Map<String, Object> res = providerService.create(ctx.body());
        audit(p, String.valueOf(res.get("providerId")), ctx);
        ctx.respond(201, res);
        return null;
    }

    private Object update(HttpContext ctx) {
        Principal p = authFilter.requireRole(ctx, Role.ADMIN);
        Map<String, Object> res = providerService.update(ctx.pathParam("id"), ctx.body());
        audit(p, ctx.pathParam("id"), ctx);
        ctx.respond(200, res);
        return null;
    }

    private void audit(Principal p, String target, HttpContext ctx) {
        if (auditService != null) {
            auditService.record(p.userId(), AuditService.ACTION_PROVIDER_UPDATE, target, ctx.remoteAddress());
        }
    }
}
