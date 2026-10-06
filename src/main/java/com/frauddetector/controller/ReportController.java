package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Role;
import com.frauddetector.service.ReportService;

/**
 * Report endpoints (PRD section 35), restricted to ADMIN and CLAIM_OFFICER:
 * <ul>
 *   <li>{@code GET /api/reports/claim/{id}} - single-claim report</li>
 *   <li>{@code GET /api/reports/provider/{id}} - per-provider report</li>
 *   <li>{@code GET /api/reports/fraud-analytics} - portfolio-wide analytics</li>
 * </ul>
 *
 * <p>The literal {@code /api/reports/fraud-analytics} route is registered before
 * the templated routes so it is matched ahead of any {@code {id}} template under
 * {@code /api/reports/...}.
 */
public final class ReportController {

    private final ReportService reportService;
    private final AuthFilter authFilter;

    public ReportController(ReportService reportService, AuthFilter authFilter) {
        this.reportService = reportService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        router.get("/api/reports/fraud-analytics", this::fraudAnalytics);
        router.get("/api/reports/claim/{id}", this::claimReport);
        router.get("/api/reports/provider/{id}", this::providerReport);
    }

    private Object fraudAnalytics(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN, Role.CLAIM_OFFICER);
        ctx.respond(200, reportService.fraudAnalytics());
        return null;
    }

    private Object claimReport(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN, Role.CLAIM_OFFICER);
        ctx.respond(200, reportService.claimReport(ctx.pathParam("id")));
        return null;
    }

    private Object providerReport(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN, Role.CLAIM_OFFICER);
        ctx.respond(200, reportService.providerReport(ctx.pathParam("id")));
        return null;
    }
}
