package com.frauddetector.controller;

import com.frauddetector.domain.Evidence;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Investigation;
import com.frauddetector.dto.FraudAnalysisResponse;
import com.frauddetector.dto.InvestigationResponse;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.AuditService;
import com.frauddetector.service.ClaimService;
import com.frauddetector.service.EvidenceService;
import com.frauddetector.service.InvestigationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Investigation + analysis endpoints (PRD sections 17-19, Phase 3):
 * <ul>
 *   <li>{@code GET /api/claims/high-risk} - claims with level HIGH/CRITICAL (any authenticated role)</li>
 *   <li>{@code GET /api/claims/{id}/analysis} - scores + risk factors (any authenticated role)</li>
 *   <li>{@code GET /api/investigations} - list (INVESTIGATOR/ADMIN/CLAIM_OFFICER)</li>
 *   <li>{@code GET /api/investigations/{id}} - one, with evidence (INVESTIGATOR/ADMIN/CLAIM_OFFICER)</li>
 *   <li>{@code POST /api/investigations} - open (INVESTIGATOR/ADMIN only)</li>
 *   <li>{@code PATCH /api/investigations/{id}} - record decision (INVESTIGATOR/ADMIN only)</li>
 *   <li>{@code POST|GET /api/investigations/{id}/evidence} - upload / list evidence (INVESTIGATOR/ADMIN)</li>
 *   <li>{@code GET /api/evidence/{id}/download} - download an evidence file (INVESTIGATOR/ADMIN)</li>
 * </ul>
 *
 * <p>{@code register} must run before {@link ClaimController#register} so the
 * literal {@code /api/claims/high-risk} route is matched ahead of the
 * {@code /api/claims/{id}} template.
 */
public final class InvestigationController {

    private final InvestigationService investigationService;
    private final ClaimService claimService;
    private final AuthFilter authFilter;
    private final AuditService auditService;
    private EvidenceService evidenceService;

    public InvestigationController(InvestigationService investigationService,
                                   ClaimService claimService, AuthFilter authFilter,
                                   AuditService auditService) {
        this.investigationService = investigationService;
        this.claimService = claimService;
        this.authFilter = authFilter;
        this.auditService = auditService;
    }

    public InvestigationController withEvidence(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
        return this;
    }

    public void register(Router router) {
        // Literal route first so it wins over /api/claims/{id}.
        router.get("/api/claims/high-risk", this::highRisk);
        router.get("/api/claims/{id}/analysis", this::analysis);
        router.get("/api/investigations", this::list);
        router.post("/api/investigations", this::open);
        router.get("/api/investigations/{id}", this::getOne);
        router.patch("/api/investigations/{id}", this::decide);
        router.get("/api/investigations/{id}/evidence", this::listEvidence);
        router.post("/api/investigations/{id}/evidence", this::uploadEvidence);
        router.get("/api/evidence/{id}/download", this::download);
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

    private Object list(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN, Role.CLAIM_OFFICER);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Investigation i : investigationService.list(ctx.queryParams())) {
            out.add(InvestigationResponse.of(i).toJson());
        }
        ctx.respond(200, out);
        return null;
    }

    private Object getOne(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN, Role.CLAIM_OFFICER);
        Investigation i = investigationService.get(ctx.pathParam("id"));
        Map<String, Object> json = new LinkedHashMap<>(InvestigationResponse.of(i).toJson());
        if (evidenceService != null) {
            json.put("evidence", evidenceJson(evidenceService.list(i.getInvestigationId())));
        }
        ctx.respond(200, json);
        return null;
    }

    private Object open(HttpContext ctx) {
        Principal principal = authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        Map<String, Object> body = ctx.body();
        String claimId = str(body, "claimId");
        String notes = str(body, "notes");
        Investigation investigation = investigationService.open(claimId, notes, principal);
        if (auditService != null) {
            auditService.record(principal.userId(), AuditService.ACTION_INVESTIGATION_OPEN,
                    investigation.getInvestigationId(), ctx.remoteAddress());
        }
        ctx.respond(201, InvestigationResponse.of(investigation).toJson());
        return null;
    }

    private Object decide(HttpContext ctx) {
        Principal principal = authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        Map<String, Object> body = ctx.body();
        String decision = str(body, "decision");
        String notes = str(body, "notes");
        Investigation updated = investigationService.decide(ctx.pathParam("id"), decision, notes);
        if (auditService != null) {
            auditService.record(principal.userId(), AuditService.ACTION_INVESTIGATION_DECISION,
                    updated.getInvestigationId(), ctx.remoteAddress());
        }
        ctx.respond(200, InvestigationResponse.of(updated).toJson());
        return null;
    }

    private Object listEvidence(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        requireEvidence();
        ctx.respond(200, evidenceJson(evidenceService.list(ctx.pathParam("id"))));
        return null;
    }

    private Object uploadEvidence(HttpContext ctx) {
        Principal principal = authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        requireEvidence();
        Map<String, Object> body = ctx.body();
        Evidence e = evidenceService.upload(ctx.pathParam("id"), str(body, "fileName"),
                str(body, "contentType"), str(body, "dataBase64"), str(body, "description"),
                principal.userId());
        if (auditService != null) {
            auditService.record(principal.userId(), AuditService.ACTION_EVIDENCE_UPLOAD,
                    e.getEvidenceId(), ctx.remoteAddress());
        }
        ctx.respond(201, evidenceJson(e));
        return null;
    }

    private Object download(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.INVESTIGATOR, Role.ADMIN);
        requireEvidence();
        Evidence e = evidenceService.get(ctx.pathParam("id"));
        byte[] bytes = evidenceService.read(e);
        ctx.exchange().getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + e.getFileName() + "\"");
        ctx.respondRaw(200, e.getContentType(), bytes);
        return null;
    }

    private void requireEvidence() {
        if (evidenceService == null) {
            throw new com.frauddetector.http.ApiException(503, "Evidence storage is not configured");
        }
    }

    static List<Map<String, Object>> evidenceJson(List<Evidence> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Evidence e : list) {
            out.add(evidenceJson(e));
        }
        return out;
    }

    static Map<String, Object> evidenceJson(Evidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidenceId", e.getEvidenceId());
        m.put("investigationId", e.getInvestigationId());
        m.put("fileName", e.getFileName());
        m.put("contentType", e.getContentType());
        m.put("sizeBytes", e.getSizeBytes());
        m.put("description", e.getDescription());
        m.put("uploadedBy", e.getUploadedBy());
        m.put("uploadedAt", e.getUploadedAt() == null ? null : e.getUploadedAt().toString());
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }
}
