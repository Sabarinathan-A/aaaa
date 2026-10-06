package com.frauddetector.controller;

import com.frauddetector.dto.ClaimResponse;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.ClaimService;

import java.util.List;
import java.util.Map;

/**
 * Claim endpoints:
 * <ul>
 *   <li>{@code POST /api/claims} - submit (roles PROVIDER, CLAIM_OFFICER, ADMIN)</li>
 *   <li>{@code GET /api/claims/{id}} - read one (any authenticated role)</li>
 *   <li>{@code GET /api/claims} - list (any authenticated role)</li>
 * </ul>
 */
public final class ClaimController {

    private final ClaimService claimService;
    private final AuthFilter authFilter;

    public ClaimController(ClaimService claimService, AuthFilter authFilter) {
        this.claimService = claimService;
        this.authFilter = authFilter;
    }

    public void register(Router router) {
        router.post("/api/claims", this::submit);
        router.get("/api/claims/{id}", this::getOne);
        router.get("/api/claims", this::list);
    }

    private Object submit(HttpContext ctx) {
        Principal principal = authFilter.requireRole(ctx, Role.PROVIDER, Role.CLAIM_OFFICER, Role.ADMIN);
        SubmitClaimRequest req = SubmitClaimRequest.fromJson(ctx.body());
        ClaimResponse res = claimService.submitClaim(req, principal);
        ctx.respond(201, res.toJson());
        return null;
    }

    private Object getOne(HttpContext ctx) {
        authFilter.authenticate(ctx);
        ClaimResponse res = claimService.getClaim(ctx.pathParam("id"));
        ctx.respond(200, res.toJson());
        return null;
    }

    private Object list(HttpContext ctx) {
        authFilter.authenticate(ctx);
        List<Map<String, Object>> all = claimService.listClaims(ctx.queryParams());
        ctx.respond(200, all);
        return null;
    }
}
