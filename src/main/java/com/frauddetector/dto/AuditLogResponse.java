package com.frauddetector.dto;

import com.frauddetector.security.AuditLog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Response view of an {@link AuditLog} entry, serialized via the Json codec. */
public final class AuditLogResponse {

    private final AuditLog entry;

    private AuditLogResponse(AuditLog entry) {
        this.entry = entry;
    }

    public static AuditLogResponse of(AuditLog entry) {
        return new AuditLogResponse(entry);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", entry.getId());
        json.put("userId", entry.getUserId());
        json.put("action", entry.getAction());
        json.put("targetId", entry.getTargetId());
        json.put("timestamp", entry.getTimestamp() == null ? null : entry.getTimestamp().toString());
        json.put("ip", entry.getIp());
        return json;
    }
}
