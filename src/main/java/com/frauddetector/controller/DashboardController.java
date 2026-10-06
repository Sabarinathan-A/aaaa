package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.service.DashboardService;

/**
 * Dashboard endpoint (PRD section 20). {@code GET /api/dashboard} returns KPI
 * cards and chart datasets for any authenticated role.
 */
public final class DashboardController {

    private final DashboardService dashboardService;
    private final AuthFilter authFilter;

    public DashboardController(DashboardService dashboardService, AuthFilter authFilter) {
        this.dashboardService = dashboardService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        router.get("/api/dashboard", this::dashboard);
    }

    private Object dashboard(HttpContext ctx) {
        authFilter.authenticate(ctx);
        ctx.respond(200, dashboardService.build());
        return null;
    }
}
