package com.frauddetector.dto;

import java.util.Map;

/** Request body for {@code POST /api/auth/login}. */
public record LoginRequest(String email, String password) {

    public static LoginRequest fromJson(Map<String, Object> json) {
        return new LoginRequest(asString(json.get("email")), asString(json.get("password")));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
