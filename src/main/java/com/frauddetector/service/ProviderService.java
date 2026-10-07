package com.frauddetector.service;

import com.frauddetector.domain.Provider;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.service.ml.DuplicateDetector;
import com.frauddetector.service.ml.ProviderRiskService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider read + analytics service (PRD section 13 completion). Recomputes each
 * provider's risk score from the current claim population using
 * {@link ProviderRiskService} and surfaces providers (with that score and claim
 * stats) via the API.
 */
public final class ProviderService {

    private final ProviderRepository providers;
    private final ClaimRepository claims;
    private final ProviderRiskService providerRiskService;

    public ProviderService(ProviderRepository providers, ClaimRepository claims) {
        this.providers = providers;
        this.claims = claims;
        this.providerRiskService = new ProviderRiskService(claims, new DuplicateDetector(claims));
    }

    /**
     * Recompute every provider's risk score from current claims and persist the
     * refreshed {@link Provider} back into the repository. Called at startup
     * (after seeding) and on demand so the stored score reflects the data.
     */
    public void recomputeAll() {
        for (Provider p : providers.findAll()) {
            double score = providerRiskService.assess(p.getProviderId()).riskScore;
            BigDecimal rounded = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
            providers.save(new Provider(p.getProviderId(), p.getProviderName(), p.getHospital(),
                    p.getLocation(), p.getSpecialization(), rounded));
        }
    }

    /** All providers with their (freshly recomputed) risk score and claim count. */
    public List<Map<String, Object>> listProviders() {
        recomputeAll();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Provider p : providers.findAll()) {
            list.add(toJson(p));
        }
        return list;
    }

    /** A single provider with its risk score and claim stats, or 404. */
    public Map<String, Object> getProvider(String providerId) {
        recomputeAll();
        Provider p = providers.findById(providerId)
                .orElseThrow(() -> new ApiException(404, "Provider '" + providerId + "' not found"));
        return toJson(p);
    }

    /** Register a new provider (ADMIN). 409 if the id already exists. */
    public Map<String, Object> create(Map<String, Object> body) {
        String id = text(body, "providerId");
        String name = text(body, "providerName");
        if (id == null || name == null) {
            throw new ApiException(400, "providerId and providerName are required");
        }
        if (!id.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new ApiException(400, "providerId may only contain letters, digits, '-' and '_' (max 40)");
        }
        if (providers.existsById(id)) {
            throw new ApiException(409, "Provider '" + id + "' already exists");
        }
        providers.save(new Provider(id, name, text(body, "hospital"), text(body, "location"),
                text(body, "specialization"), BigDecimal.ZERO));
        return getProvider(id);
    }

    /** Update a provider's descriptive fields (ADMIN). The risk score stays computed. */
    public Map<String, Object> update(String providerId, Map<String, Object> body) {
        Provider p = providers.findById(providerId)
                .orElseThrow(() -> new ApiException(404, "Provider '" + providerId + "' not found"));
        providers.save(new Provider(providerId,
                orElse(text(body, "providerName"), p.getProviderName()),
                orElse(text(body, "hospital"), p.getHospital()),
                orElse(text(body, "location"), p.getLocation()),
                orElse(text(body, "specialization"), p.getSpecialization()),
                p.getRiskScore()));
        return getProvider(providerId);
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String orElse(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private Map<String, Object> toJson(Provider p) {
        ProviderRiskService.Result r = providerRiskService.assess(p.getProviderId());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("providerId", p.getProviderId());
        json.put("providerName", p.getProviderName());
        json.put("hospital", p.getHospital());
        json.put("location", p.getLocation());
        json.put("specialization", p.getSpecialization());
        json.put("riskScore", p.getRiskScore() == null ? "0" : p.getRiskScore().toPlainString());
        json.put("claimCount", r.claimCount);
        json.put("flaggedRate", round4(r.flaggedRate));
        json.put("averageClaim", round2(r.avgAmount));
        return json;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
