package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Investigation;
import com.frauddetector.domain.Provider;
import com.frauddetector.domain.RiskFactor;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.service.ml.BillingAnomalyService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Report generation (PRD section 35). Produces structured report payloads that
 * serialize directly through the Json codec:
 * <ul>
 *   <li>claim report - a single claim with its risk breakdown and investigation</li>
 *   <li>provider report - per-provider volume, averages and fraud rate</li>
 *   <li>fraud-analytics report - portfolio-wide totals and top-risk rankings</li>
 * </ul>
 */
public final class ReportService {

    private final ClaimRepository claims;
    private final FraudAnalysisRepository analyses;
    private final ProviderRepository providers;
    private final InvestigationRepository investigations;
    private final ThresholdConfig thresholds;
    private final BillingAnomalyService billingAnomalyService;

    public ReportService(ClaimRepository claims, FraudAnalysisRepository analyses,
                         ProviderRepository providers, InvestigationRepository investigations,
                         ThresholdConfig thresholds) {
        this.claims = claims;
        this.analyses = analyses;
        this.providers = providers;
        this.investigations = investigations;
        this.thresholds = thresholds;
        this.billingAnomalyService = new BillingAnomalyService(claims);
    }

    /** Single-claim report (id, patient, provider, treatment, amount, risk, factors, investigation). */
    public Map<String, Object> claimReport(String claimId) {
        Claim c = claims.findById(claimId)
                .orElseThrow(() -> new ApiException(404, "Claim '" + claimId + "' not found"));
        FraudAnalysis a = analyses.findByClaimId(claimId).orElse(null);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("claimId", c.getClaimId());
        report.put("patientId", c.getPatientId());
        report.put("providerId", c.getProviderId());
        report.put("hospitalId", c.getHospitalId());
        report.put("diagnosis", c.getDiagnosis());
        report.put("treatment", c.getProcedure());
        report.put("amount", c.getTotalBilledAmount() == null ? null : c.getTotalBilledAmount().toPlainString());
        report.put("claimStatus", c.getClaimStatus());
        report.put("riskScore", a == null ? null : a.getFinalRiskScore());
        report.put("riskLevel", a == null ? null : a.getRiskLevel());
        report.put("riskFactors", riskFactors(a));

        List<Investigation> invs = investigations == null
                ? List.of() : investigations.findByClaimId(claimId);
        Investigation latest = invs.stream()
                .max(Comparator.comparing(Investigation::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        report.put("investigationStatus", latest == null ? "NONE" : latest.getStatus());
        report.put("investigationDecision",
                latest == null || latest.getDecision() == null ? null : latest.getDecision().name());
        return report;
    }

    /** Per-provider report (total claims, average claim, flagged claims, fraud rate, risk score). */
    public Map<String, Object> providerReport(String providerId) {
        Provider p = providers.findById(providerId)
                .orElseThrow(() -> new ApiException(404, "Provider '" + providerId + "' not found"));
        List<Claim> providerClaims = claims.findByProviderId(providerId);
        int total = providerClaims.size();
        int flagged = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (Claim c : providerClaims) {
            if (c.getTotalBilledAmount() != null) {
                sum = sum.add(c.getTotalBilledAmount());
            }
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                flagged++;
            }
        }
        double fraudRate = total == 0 ? 0.0 : (double) flagged / total;
        BigDecimal avg = total == 0 ? BigDecimal.ZERO
                : sum.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("providerId", p.getProviderId());
        report.put("providerName", p.getProviderName());
        report.put("totalClaims", total);
        report.put("averageClaim", avg.toPlainString());
        report.put("flaggedClaims", flagged);
        report.put("fraudRate", round4(fraudRate));
        report.put("riskScore", p.getRiskScore() == null ? "0" : p.getRiskScore().toPlainString());
        return report;
    }

    /** Portfolio-wide fraud analytics (totals, suspicious count, inflation, top providers/treatments, monthly trend). */
    public Map<String, Object> fraudAnalytics() {
        List<Claim> allClaims = claims.findAll();
        int total = allClaims.size();
        int suspicious = 0;
        BigDecimal inflation = BigDecimal.ZERO;

        for (Claim c : allClaims) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                suspicious++;
                BillingAnomalyService.Result r = billingAnomalyService.analyze(c);
                if (r.sampleSize > 0 && r.historicalMean > 0) {
                    double amount = c.getTotalBilledAmount() == null
                            ? 0.0 : c.getTotalBilledAmount().doubleValue();
                    double excess = amount - r.historicalMean;
                    if (excess > 0) {
                        inflation = inflation.add(BigDecimal.valueOf(excess));
                    }
                }
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalClaims", total);
        report.put("suspiciousClaims", suspicious);
        report.put("fraudRate", round4(total == 0 ? 0.0 : (double) suspicious / total));
        report.put("potentialBillingInflation", inflation.setScale(2, RoundingMode.HALF_UP).toPlainString());
        report.put("topRiskProviders", topRiskProviders(allClaims));
        report.put("topRiskTreatments", topRiskTreatments(allClaims));
        report.put("monthlyTrends", monthlyTrends(allClaims));
        return report;
    }

    private List<Map<String, Object>> topRiskProviders(List<Claim> allClaims) {
        Map<String, int[]> byProvider = new LinkedHashMap<>();
        for (Claim c : allClaims) {
            int[] agg = byProvider.computeIfAbsent(c.getProviderId(), k -> new int[2]);
            agg[1]++;
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                agg[0]++;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byProvider.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            int flagged = e.getValue()[0];
            int tot = e.getValue()[1];
            row.put("providerId", e.getKey());
            row.put("flaggedClaims", flagged);
            row.put("totalClaims", tot);
            row.put("fraudRate", round4(tot == 0 ? 0.0 : (double) flagged / tot));
            list.add(row);
        }
        list.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("flaggedClaims")).reversed());
        return list.subList(0, Math.min(5, list.size()));
    }

    private List<Map<String, Object>> topRiskTreatments(List<Claim> allClaims) {
        Map<String, int[]> byTreatment = new LinkedHashMap<>();
        for (Claim c : allClaims) {
            String proc = c.getProcedure() == null ? "(unknown)" : c.getProcedure();
            int[] agg = byTreatment.computeIfAbsent(proc, k -> new int[2]);
            agg[1]++;
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                agg[0]++;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byTreatment.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("treatment", e.getKey());
            row.put("flaggedClaims", e.getValue()[0]);
            row.put("totalClaims", e.getValue()[1]);
            list.add(row);
        }
        list.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("flaggedClaims")).reversed());
        return list.subList(0, Math.min(5, list.size()));
    }

    private List<Map<String, Object>> monthlyTrends(List<Claim> allClaims) {
        TreeMap<String, int[]> byMonth = new TreeMap<>();
        for (Claim c : allClaims) {
            LocalDate d = c.getClaimDate();
            if (d == null) {
                continue;
            }
            String key = String.format("%04d-%02d", d.getYear(), d.getMonthValue());
            int[] agg = byMonth.computeIfAbsent(key, k -> new int[2]);
            agg[1]++;
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                agg[0]++;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byMonth.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", e.getKey());
            row.put("flagged", e.getValue()[0]);
            row.put("total", e.getValue()[1]);
            list.add(row);
        }
        return list;
    }

    private static List<Map<String, Object>> riskFactors(FraudAnalysis a) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (a == null) {
            return list;
        }
        for (RiskFactor f : a.getRiskFactors()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("description", f.getDescription());
            row.put("contribution", f.getContribution());
            list.add(row);
        }
        return list;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
