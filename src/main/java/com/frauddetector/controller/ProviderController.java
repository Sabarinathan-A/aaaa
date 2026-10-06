package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.service.ProviderService;

/**
 * Provider endpoints (PRD section 13):
 * <ul>
 *   <li>{@code GET /api/providers} - all providers with recomputed risk score</li>
 *   <li>{@code GET /api/providers/{id}} - a single provider with risk score</li>
 * </ul>
 * Any authenticated role may read providers.
 */
public final class ProviderController {

    private final ProviderService providerService;
    private final AuthFilter authFilter;

    public ProviderController(ProviderService providerService, AuthFilter authFilter) {
        this.providerService = providerService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        router.get("/api/providers", this::list);
        router.get("/api/providers/{id}", this::getOne);
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
}
