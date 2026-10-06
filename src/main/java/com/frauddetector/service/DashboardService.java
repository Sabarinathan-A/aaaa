package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
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
 * Aggregates the data a web dashboard (PRD section 20, 33-35) would render:
 * KPI cards and chart datasets. All computation is in-memory over the claim and
 * analysis repositories; results are plain {@code Map}/{@code List} structures
 * that serialize directly through the Json codec.
 *
 * <p>KPI cards:
 * <ul>
 *   <li>{@code totalClaims} - all claims</li>
 *   <li>{@code flaggedClaims} - claims whose latest analysis is HIGH or CRITICAL</li>
 *   <li>{@code highRiskClaims} - same set (alias retained for the UI card)</li>
 *   <li>{@code potentialSavings} - summed billing excess over the historical
 *       normal for flagged claims</li>
 * </ul>
 */
public final class DashboardService {

    private final ClaimRepository claims;
    private final FraudAnalysisRepository analyses;
    private final ProviderRepository providers;
    private final ThresholdConfig thresholds;
    private final BillingAnomalyService billingAnomalyService;

    public DashboardService(ClaimRepository claims, FraudAnalysisRepository analyses,
                            ProviderRepository providers, ThresholdConfig thresholds) {
        this.claims = claims;
        this.analyses = analyses;
        this.providers = providers;
        this.thresholds = thresholds;
        // Use the clean-baseline billing analyzer so potentialSavings is measured
        // against the legitimate population, not against the inflated claims
        // themselves (review findings #1/#2).
        this.billingAnomalyService = new BillingAnomalyService(
                claims, analyses, thresholds,
                new com.frauddetector.service.ml.DuplicateDetector(claims));
    }

    /** Build the full dashboard payload (KPI cards + chart datasets). */
    public Map<String, Object> build() {
        List<Claim> allClaims = claims.findAll();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kpis", kpis(allClaims));
        payload.put("charts", charts(allClaims));
        return payload;
    }

    /** KPI cards only (used by tests and the controller). */
    public Map<String, Object> kpis() {
        return kpis(claims.findAll());
    }

    private Map<String, Object> kpis(List<Claim> allClaims) {
        int total = allClaims.size();
        int flagged = 0;
        int highRisk = 0;
        for (Claim c : allClaims) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                flagged++;
                highRisk++;
            }
        }
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalClaims", total);
        kpis.put("flaggedClaims", flagged);
        kpis.put("highRiskClaims", highRisk);
        kpis.put("potentialSavings", potentialSavings(allClaims).toPlainString());
        return kpis;
    }

    /**
     * Sum of the billing excess over the historical normal (mean) for every
     * flagged claim. The "excess" is {@code amount - historicalMean} when the
     * amount is above the mean and history exists; otherwise it contributes 0.
     */
    public BigDecimal potentialSavings(List<Claim> allClaims) {
        BigDecimal savings = BigDecimal.ZERO;
        for (Claim c : allClaims) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a == null || !thresholds.isHighRisk(a.getRiskLevel())) {
                continue;
            }
            BillingAnomalyService.Result r = billingAnomalyService.analyze(c);
            if (r.sampleSize > 0 && r.historicalMean > 0) {
                double amount = c.getTotalBilledAmount() == null
                        ? 0.0 : c.getTotalBilledAmount().doubleValue();
                double excess = amount - r.historicalMean;
                if (excess > 0) {
                    savings = savings.add(BigDecimal.valueOf(excess));
                }
            }
        }
        return savings.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal potentialSavings() {
        return potentialSavings(claims.findAll());
    }

    private Map<String, Object> charts(List<Claim> allClaims) {
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("claimsOverTimeByDay", claimsOverTime(allClaims, false));
        charts.put("claimsOverTimeByMonth", claimsOverTime(allClaims, true));
        charts.put("fraudTrendByMonth", fraudTrend(allClaims));
        charts.put("providerRiskRanking", providerRiskRanking(allClaims));
        charts.put("claimAmountDistribution", amountDistribution(allClaims));
        charts.put("fraudVsLegitimate", fraudVsLegitimate(allClaims));
        charts.put("highRiskProcedures", highRiskProcedures(allClaims));
        charts.put("geographicDistribution", geographicDistribution(allClaims));
        return charts;
    }

    // claims over time: [{period, count}, ...]
    private List<Map<String, Object>> claimsOverTime(List<Claim> allClaims, boolean byMonth) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (Claim c : allClaims) {
            LocalDate d = c.getClaimDate();
            if (d == null) {
                continue;
            }
            String key = byMonth
                    ? String.format("%04d-%02d", d.getYear(), d.getMonthValue())
                    : d.toString();
            counts.merge(key, 1, Integer::sum);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", e.getKey());
            row.put("count", e.getValue());
            list.add(row);
        }
        return list;
    }

    // fraud trend: [{period, flagged, total}, ...] by month
    private List<Map<String, Object>> fraudTrend(List<Claim> allClaims) {
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

    // provider risk ranking: [{providerId, riskScore, claimCount}, ...] desc
    private List<Map<String, Object>> providerRiskRanking(List<Claim> allClaims) {
        Map<String, Integer> claimCounts = new LinkedHashMap<>();
        for (Claim c : allClaims) {
            claimCounts.merge(c.getProviderId(), 1, Integer::sum);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (com.frauddetector.domain.Provider p : providers.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("providerId", p.getProviderId());
            row.put("providerName", p.getProviderName());
            row.put("riskScore", p.getRiskScore() == null ? "0" : p.getRiskScore().toPlainString());
            row.put("claimCount", claimCounts.getOrDefault(p.getProviderId(), 0));
            list.add(row);
        }
        list.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> Double.parseDouble(String.valueOf(m.get("riskScore")))).reversed());
        return list;
    }

    // claim amount distribution buckets: [{bucket, count}, ...]
    private List<Map<String, Object>> amountDistribution(List<Claim> allClaims) {
        String[] labels = {"0-1000", "1000-5000", "5000-10000", "10000-50000", "50000+"};
        int[] buckets = new int[labels.length];
        for (Claim c : allClaims) {
            double amt = c.getTotalBilledAmount() == null ? 0.0 : c.getTotalBilledAmount().doubleValue();
            int idx;
            if (amt < 1000) {
                idx = 0;
            } else if (amt < 5000) {
                idx = 1;
            } else if (amt < 10000) {
                idx = 2;
            } else if (amt < 50000) {
                idx = 3;
            } else {
                idx = 4;
            }
            buckets[idx]++;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", labels[i]);
            row.put("count", buckets[i]);
            list.add(row);
        }
        return list;
    }

    // fraud vs legitimate: [{label, count}, ...]
    private List<Map<String, Object>> fraudVsLegitimate(List<Claim> allClaims) {
        int fraud = 0;
        int legit = 0;
        for (Claim c : allClaims) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                fraud++;
            } else {
                legit++;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(labelCount("Suspicious", fraud));
        list.add(labelCount("Legitimate", legit));
        return list;
    }

    // high-risk procedures: [{procedure, flagged, total}, ...] desc by flagged
    private List<Map<String, Object>> highRiskProcedures(List<Claim> allClaims) {
        Map<String, int[]> byProc = new LinkedHashMap<>();
        for (Claim c : allClaims) {
            String proc = c.getProcedure() == null ? "(unknown)" : c.getProcedure();
            int[] agg = byProc.computeIfAbsent(proc, k -> new int[2]);
            agg[1]++;
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && thresholds.isHighRisk(a.getRiskLevel())) {
                agg[0]++;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byProc.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("procedure", e.getKey());
            row.put("flagged", e.getValue()[0]);
            row.put("total", e.getValue()[1]);
            list.add(row);
        }
        list.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("flagged")).reversed());
        return list;
    }

    // geographic distribution by hospital: [{location, count}, ...]
    private List<Map<String, Object>> geographicDistribution(List<Claim> allClaims) {
        Map<String, Integer> byHospital = new LinkedHashMap<>();
        for (Claim c : allClaims) {
            String loc = c.getHospitalId() == null ? "(unknown)" : c.getHospitalId();
            byHospital.merge(loc, 1, Integer::sum);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byHospital.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("location", e.getKey());
            row.put("count", e.getValue());
            list.add(row);
        }
        return list;
    }

    private static Map<String, Object> labelCount(String label, int count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("count", count);
        return row;
    }
}
