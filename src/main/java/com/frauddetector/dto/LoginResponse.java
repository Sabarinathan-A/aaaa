package com.frauddetector.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** Response body for a successful login. */
public record LoginResponse(String token, String role, String name) {

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("token", token);
        json.put("role", role);
        json.put("name", name);
        return json;
    }
}
