package com.frauddetector.dto;

import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.RiskFactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Response view of a {@link FraudAnalysis}, serialized via the Json codec. */
public final class FraudAnalysisResponse {

    private final FraudAnalysis analysis;

    private FraudAnalysisResponse(FraudAnalysis analysis) {
        this.analysis = analysis;
    }

    public static FraudAnalysisResponse of(FraudAnalysis analysis) {
        return new FraudAnalysisResponse(analysis);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("analysisId", analysis.getAnalysisId());
        json.put("claimId", analysis.getClaimId());
        json.put("fraudProbability", analysis.getFraudProbability());
        json.put("billingAnomalyScore", analysis.getBillingAnomalyScore());
        json.put("duplicateScore", analysis.getDuplicateScore());
        json.put("providerRiskScore", analysis.getProviderRiskScore());
        json.put("patientRiskScore", analysis.getPatientRiskScore());
        json.put("finalRiskScore", analysis.getFinalRiskScore());
        json.put("riskLevel", analysis.getRiskLevel());
        json.put("modelVersion", analysis.getModelVersion());
        json.put("riskFactors", riskFactorsJson(analysis.getRiskFactors()));
        json.put("createdAt", analysis.getCreatedAt() == null ? null : analysis.getCreatedAt().toString());
        return json;
    }

    private static List<Map<String, Object>> riskFactorsJson(List<RiskFactor> factors) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RiskFactor factor : factors) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("description", factor.getDescription());
            item.put("contribution", factor.getContribution());
            list.add(item);
        }
        return list;
    }
}
