package com.frauddetector.controller;

import com.frauddetector.dto.LoginRequest;
import com.frauddetector.dto.LoginResponse;
import com.frauddetector.http.ApiException;
import com.frauddetector.http.HttpContext;
import com.frauddetector.http.Router;
import com.frauddetector.service.AuthService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication endpoints. {@code POST /api/auth/login} is public;
 * {@code POST /api/auth/logout} is a no-op for stateless tokens (the client
 * simply discards the token).
 */
public final class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public void register(Router router) {
        router.post("/api/auth/login", this::login);
        router.post("/api/auth/logout", this::logout);
    }

    private Object login(HttpContext ctx) {
        LoginRequest req = LoginRequest.fromJson(ctx.body());
        if (req.email() == null || req.password() == null) {
            throw new ApiException(400, "email and password are required");
        }
        LoginResponse res = authService.login(req.email(), req.password());
        ctx.respond(200, res.toJson());
        return null;
    }

    private Object logout(HttpContext ctx) {
        // Stateless tokens: nothing to revoke server-side; client drops the token.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "LOGGED_OUT");
        ctx.respond(200, body);
        return null;
    }
}
