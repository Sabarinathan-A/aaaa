package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.AuditService;
import com.frauddetector.service.ClaimService;
import com.frauddetector.service.MlService;
import com.frauddetector.service.ml.LogisticModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Model monitoring endpoints (PRD sections 5.1 "Model monitoring", 27, 31 "Model update"):
 * <ul>
 *   <li>{@code GET /api/ml/model} - active model card: weights, hold-out metrics
 *       (accuracy, precision, recall, F1, ROC-AUC, confusion matrix), history (ADMIN, CLAIM_OFFICER, INVESTIGATOR)</li>
 *   <li>{@code POST /api/ml/retrain} - retrain with investigator feedback; body
 *       {@code {"rescore": true}} also re-scores every stored claim (ADMIN)</li>
 * </ul>
 */
public final class MlController {

    private final MlService mlService;
    private final ClaimService claimService;
    private final AuthFilter authFilter;
    private final AuditService auditService;

    public MlController(MlService mlService, ClaimService claimService, AuthFilter authFilter, AuditService auditService) {
        this.mlService = mlService;
        this.claimService = claimService;
        this.authFilter = authFilter;
        this.auditService = auditService;
    }

    public void register(Router router) {
        router.get("/api/ml/model", this::model);
        router.post("/api/ml/retrain", this::retrain);
    }

    private Object model(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN, Role.CLAIM_OFFICER, Role.INVESTIGATOR);
        ctx.respond(200, mlService.describe());
        return null;
    }

    private Object retrain(HttpContext ctx) {
        Principal p = authFilter.requireRole(ctx, Role.ADMIN);
        boolean rescore = Boolean.TRUE.equals(ctx.body().get("rescore"));
        LogisticModel m = mlService.train();
        int rescored = rescore ? claimService.rescoreAll() : 0;
        if (auditService != null) {
            auditService.record(p.userId(), AuditService.ACTION_MODEL_UPDATE, m.version(), ctx.remoteAddress());
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("version", m.version());
        res.put("feedbackSamples", m.feedbackSamples());
        res.put("metrics", m.metrics().toJson());
        res.put("rescoredClaims", rescored);
        ctx.respond(200, res);
        return null;
    }
}
