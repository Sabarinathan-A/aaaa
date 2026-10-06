package com.frauddetector.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** Standard error body. */
public record ErrorResponse(int status, String message) {

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("status", status);
        json.put("message", message);
        return json;
    }
}
