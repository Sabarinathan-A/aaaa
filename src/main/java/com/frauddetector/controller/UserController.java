package com.frauddetector.controller;

import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.security.AuthFilter;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.service.AuditService;
import com.frauddetector.service.UserService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User management + profile endpoints (PRD sections 5.1, 33):
 * <ul>
 *   <li>{@code GET /api/me} - the caller's profile (any authenticated role)</li>
 *   <li>{@code POST /api/me/password} - change own password (any authenticated role)</li>
 *   <li>{@code GET|POST /api/users} - list / create users (ADMIN)</li>
 *   <li>{@code GET|PATCH /api/users/{id}} - read / update role, status, name, password (ADMIN)</li>
 * </ul>
 */
public final class UserController {

    private final UserService userService;
    private final AuthFilter authFilter;
    private final AuditService auditService;

    public UserController(UserService userService, AuthFilter authFilter, AuditService auditService) {
        this.userService = userService;
        this.authFilter = authFilter;
        this.auditService = auditService;
    }

    public void register(Router router) {
        router.get("/api/me", this::me);
        router.post("/api/me/password", this::changePassword);
        router.get("/api/users", this::list);
        router.post("/api/users", this::create);
        router.get("/api/users/{id}", this::getOne);
        router.patch("/api/users/{id}", this::update);
    }

    private Object me(HttpContext ctx) {
        Principal p = authFilter.authenticate(ctx);
        ctx.respond(200, userService.get(p.userId()));
        return null;
    }

    private Object changePassword(HttpContext ctx) {
        Principal p = authFilter.authenticate(ctx);
        Map<String, Object> body = ctx.body();
        userService.changeOwnPassword(p.userId(), str(body, "currentPassword"), str(body, "newPassword"));
        audit(p, AuditService.ACTION_USER_UPDATE, p.userId(), ctx);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "PASSWORD_CHANGED");
        ctx.respond(200, res);
        return null;
    }

    private Object list(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN);
        ctx.respond(200, userService.list());
        return null;
    }

    private Object getOne(HttpContext ctx) {
        authFilter.requireRole(ctx, Role.ADMIN);
        ctx.respond(200, userService.get(ctx.pathParam("id")));
        return null;
    }

    private Object create(HttpContext ctx) {
        Principal p = authFilter.requireRole(ctx, Role.ADMIN);
        Map<String, Object> res = userService.create(ctx.body());
        audit(p, AuditService.ACTION_USER_CREATE, String.valueOf(res.get("id")), ctx);
        ctx.respond(201, res);
        return null;
    }

    private Object update(HttpContext ctx) {
        Principal p = authFilter.requireRole(ctx, Role.ADMIN);
        Map<String, Object> res = userService.update(ctx.pathParam("id"), ctx.body(), p.userId());
        audit(p, AuditService.ACTION_USER_UPDATE, ctx.pathParam("id"), ctx);
        ctx.respond(200, res);
        return null;
    }

    private void audit(Principal p, String action, String target, HttpContext ctx) {
        if (auditService != null) {
            auditService.record(p.userId(), action, target, ctx.remoteAddress());
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }
}
