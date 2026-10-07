package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Patient;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.security.DataMasker;
import com.frauddetector.security.Role;
import com.frauddetector.service.ml.PatientRiskService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Patient registry + behavior view (PRD sections 14, 30, 33). Responses mask the
 * patient's name and insurance id for roles that may not see them.
 */
public final class PatientService {

    private final PatientRepository patients;
    private final ClaimRepository claims;
    private final FraudAnalysisRepository analyses;
    private final PatientRiskService patientRisk;

    public PatientService(PatientRepository patients, ClaimRepository claims, FraudAnalysisRepository analyses) {
        this.patients = patients;
        this.claims = claims;
        this.analyses = analyses;
        this.patientRisk = new PatientRiskService(claims);
    }

    public List<Map<String, Object>> list(Role viewer, String query) {
        List<Map<String, Object>> out = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Patient> all = patients.findAll();
        all.sort(Comparator.comparing(Patient::getPatientId));
        for (Patient p : all) {
            boolean match = q.isEmpty()
                    || p.getPatientId().toLowerCase().contains(q)
                    || (DataMasker.canViewSensitive(viewer) && p.getName() != null
                        && p.getName().toLowerCase().contains(q));
            if (match) {
                out.add(summary(p, viewer));
            }
        }
        return out;
    }

    /** Patient detail with claim history and behavior risk. */
    public Map<String, Object> get(String patientId, Role viewer) {
        Patient p = patients.findById(patientId)
                .orElseThrow(() -> new ApiException(404, "Patient '" + patientId + "' not found"));
        Map<String, Object> json = summary(p, viewer);
        List<Claim> history = new ArrayList<>(claims.findByPatientId(patientId));
        history.sort(Comparator.comparing(Claim::getClaimDate,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Claim c : history) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("claimId", c.getClaimId());
            r.put("claimDate", c.getClaimDate() == null ? null : c.getClaimDate().toString());
            r.put("providerId", c.getProviderId());
            r.put("hospitalId", c.getHospitalId());
            r.put("procedure", c.getProcedure());
            r.put("totalBilledAmount", c.getTotalBilledAmount() == null ? null : c.getTotalBilledAmount().toPlainString());
            r.put("claimStatus", c.getClaimStatus());
            r.put("riskLevel", a == null ? null : a.getRiskLevel());
            r.put("finalRiskScore", a == null ? null : a.getFinalRiskScore());
            rows.add(r);
        }
        json.put("claims", rows);
        if (!history.isEmpty()) {
            PatientRiskService.Result risk = patientRisk.assess(history.get(0));
            Map<String, Object> behavior = new LinkedHashMap<>();
            behavior.put("riskScore", round4(risk.riskScore));
            behavior.put("claimsInWindow", risk.claimsInWindow);
            behavior.put("repeatedTreatments", risk.repeatedTreatments);
            behavior.put("maxHospitalsForOneTreatment", risk.maxHospitalsForOneTreatment);
            json.put("behavior", behavior);
        }
        return json;
    }

    /** Register a patient (ADMIN / CLAIM_OFFICER). */
    public Map<String, Object> create(Map<String, Object> body, Role viewer) {
        String id = text(body, "patientId");
        String name = text(body, "name");
        if (id == null || name == null) {
            throw new ApiException(400, "patientId and name are required");
        }
        if (!id.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new ApiException(400, "patientId may only contain letters, digits, '-' and '_' (max 40)");
        }
        if (patients.existsById(id)) {
            throw new ApiException(409, "Patient '" + id + "' already exists");
        }
        int age;
        try {
            age = Integer.parseInt(String.valueOf(body.getOrDefault("age", "0")).replaceAll("\\.0$", ""));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "age must be a whole number");
        }
        if (age < 0 || age > 130) {
            throw new ApiException(400, "age must be between 0 and 130");
        }
        Patient p = new Patient(id, name, age, text(body, "gender"), text(body, "location"), text(body, "insuranceId"));
        patients.save(p);
        return summary(p, viewer);
    }

    private Map<String, Object> summary(Patient p, Role viewer) {
        boolean full = DataMasker.canViewSensitive(viewer);
        List<Claim> history = claims.findByPatientId(p.getPatientId());
        BigDecimal total = BigDecimal.ZERO;
        for (Claim c : history) {
            if (c.getTotalBilledAmount() != null) {
                total = total.add(c.getTotalBilledAmount());
            }
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("patientId", p.getPatientId());
        json.put("name", full ? p.getName() : DataMasker.maskName(p.getName()));
        json.put("age", p.getAge());
        json.put("gender", p.getGender());
        json.put("location", p.getLocation());
        json.put("insuranceId", full ? p.getInsuranceId() : DataMasker.maskId(p.getInsuranceId()));
        json.put("masked", !full);
        json.put("claimCount", history.size());
        json.put("totalBilled", total.toPlainString());
        return json;
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
