package com.frauddetector.controller;

import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Investigation;
import com.frauddetector.dto.FraudAnalysisResponse;
import com.frauddetector.dto.InvestigationResponse;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.ClaimService;
import com.frauddetector.service.InvestigationService;

import java.util.List;
import java.util.Map;

/**
 * Investigation + analysis endpoints (PRD sections 17-19, Phase 3):
 * <ul>
 *   <li>{@code GET /api/claims/high-risk} - claims with level HIGH/CRITICAL (any authenticated role)</li>
 *   <li>{@code GET /api/claims/{id}/analysis} - scores + risk factors (any authenticated role)</li>
 *   <li>{@code POST /api/investigations} - open (INVESTIGATOR/ADMIN only)</li>
 *   <li>{@code PATCH /api/investigations/{id}} - record decision (INVESTIGATOR/ADMIN only)</li>
 * </ul>
 *
 * <p>The decision endpoints demonstrate the 403 role path: a CLAIM_OFFICER or
 * PROVIDER token is rejected with 403 by {@link AuthFilter#requireRole}.
 *
 * <p>{@code register} must run before {@link ClaimController#register} so the
 * literal {@code /api/claims/high-risk} route is matched ahead of the
 * {@code /api/claims/{id}} template.
 */
public final class InvestigationController {

    private final InvestigationService investigationService;
    private final ClaimService claimService;
    private final AuthFilter authFilter;

    public InvestigationController(InvestigationService investigationService,
                                   ClaimService claimService, AuthFilter authFilter) {
        this.investigationService = investigationService;
        this.claimService = claimService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        // Literal route first so it wins over /api/claims/{id}.
        router.get("/api/claims/high-risk", this::highRisk);
        router.get("/api/claims/{id}/analysis", this::analysis);
        router.post("/api/investigations", this::open);
        router.patch("/api/investigations/{id}", this::decide);
    }

    private Object highRisk(HttpContext ctx) {
        authFilter.authenticate(ctx);
        List<Map<String, Object>> flagged = claimService.listHighRisk();
        ctx.respond(200, flagged);
        return null;
    }

    private Object analysis(HttpContext ctx) {
        authFilter.authenticate(ctx);
        FraudAnalysis a = claimService.getAnalysis(ctx.pathParam("id"));
        ctx.respond(200, FraudAnalysisResponse.of(a).toJson());
        return null;
    }

    private Object open(HttpContext ctx) {
        Principal principal = authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        Map<String, Object> body = ctx.body();
        String claimId = str(body, "claimId");
        String notes = str(body, "notes");
        Investigation investigation = investigationService.open(claimId, notes, principal);
        ctx.respond(201, InvestigationResponse.of(investigation).toJson());
        return null;
    }

    private Object decide(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        Map<String, Object> body = ctx.body();
        String decision = str(body, "decision");
        String notes = str(body, "notes");
        Investigation updated = investigationService.decide(ctx.pathParam("id"), decision, notes);
        ctx.respond(200, InvestigationResponse.of(updated).toJson());
        return null;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }
}
