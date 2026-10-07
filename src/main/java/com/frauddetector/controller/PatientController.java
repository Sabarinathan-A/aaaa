package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.AuditService;
import com.frauddetector.service.PatientService;

/**
 * Patient endpoints (PRD sections 14, 30, 33). Name and insurance id are masked
 * for roles without access to sensitive data (PROVIDER).
 * <ul>
 *   <li>{@code GET /api/patients?q=} - list / search (any authenticated role)</li>
 *   <li>{@code GET /api/patients/{id}} - detail + claim history + behavior risk (any authenticated role; audited)</li>
 *   <li>{@code POST /api/patients} - register (ADMIN, CLAIM_OFFICER)</li>
 * </ul>
 */
public final class PatientController {

    private final PatientService patientService;
    private final AuthFilter authFilter;
    private final AuditService auditService;

    public PatientController(PatientService patientService, AuthFilter authFilter, AuditService auditService) {
        this.patientService = patientService;
        this.authFilter = authFilter;
        this.auditService = auditService;
    }

    public void register(Router router) {
        router.get("/api/patients", this::list);
        router.post("/api/patients", this::create);
        router.get("/api/patients/{id}", this::getOne);
    }

    private Object list(HttpContext ctx) {
        Principal p = authFilter.authenticate(ctx);
        ctx.respond(200, patientService.list(p.role(), ctx.queryParam("q")));
        return null;
    }

    private Object getOne(HttpContext ctx) {
        Principal p = authFilter.authenticate(ctx);
        Object res = patientService.get(ctx.pathParam("id"), p.role());
        if (auditService != null) {
            auditService.record(p.userId(), AuditService.ACTION_PATIENT_VIEW, ctx.pathParam("id"), ctx.remoteAddress());
        }
        ctx.respond(200, res);
        return null;
    }

    private Object create(HttpContext ctx) {
        Principal p = authFilter.requireRole(ctx, Role.ADMIN, Role.CLAIM_OFFICER);
        ctx.respond(201, patientService.create(ctx.body(), p.role()));
        return null;
    }
}
