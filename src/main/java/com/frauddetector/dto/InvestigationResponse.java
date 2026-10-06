package com.frauddetector.dto;

import com.frauddetector.domain.Investigation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Response view of an {@link Investigation}, serialized via the Json codec. */
public final class InvestigationResponse {

    private final Investigation investigation;

    private InvestigationResponse(Investigation investigation) {
        this.investigation = investigation;
    }

    public static InvestigationResponse of(Investigation investigation) {
        return new InvestigationResponse(investigation);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("investigationId", investigation.getInvestigationId());
        json.put("claimId", investigation.getClaimId());
        json.put("investigatorId", investigation.getInvestigatorId());
        json.put("status", investigation.getStatus());
        json.put("decision", investigation.getDecision() == null ? null : investigation.getDecision().name());
        json.put("notes", investigation.getNotes());
        json.put("createdAt", investigation.getCreatedAt() == null ? null : investigation.getCreatedAt().toString());
        json.put("updatedAt", investigation.getUpdatedAt() == null ? null : investigation.getUpdatedAt().toString());
        return json;
    }
}
