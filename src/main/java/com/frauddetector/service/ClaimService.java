package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.dto.ClaimResponse;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.security.Principal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Claim lifecycle service: validate + persist on submission, run the fraud
 * engine, store the analysis, read it back, and search/filter the stored
 * claims (PRD section 21).
 */
public final class ClaimService {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_UNDER_INVESTIGATION = "UNDER_INVESTIGATION";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_ESCALATED = "ESCALATED";
    public static final String STATUS_DOCUMENTS_REQUESTED = "DOCUMENTS_REQUESTED";
    public static final String STATUS_SUSPICIOUS = "SUSPICIOUS";

    /** Statuses in which the submitter may still correct the claim (PRD 5.4). */
    private static final java.util.Set<String> CORRECTABLE =
            java.util.Set.of(STATUS_SUBMITTED, STATUS_DOCUMENTS_REQUESTED);

    private final ClaimRepository claims;
    private final ValidationService validation;
    private final FraudRiskService fraudRiskService;
    private final FraudAnalysisRepository analyses;
    private NotificationService notificationService;
    private InvestigationRepository investigations;

    public ClaimService(ClaimRepository claims, ValidationService validation,
                        FraudRiskService fraudRiskService, FraudAnalysisRepository analyses) {
        this.claims = claims;
        this.validation = validation;
        this.fraudRiskService = fraudRiskService;
        this.analyses = analyses;
    }

    /**
     * Optional collaborator wiring, set during bootstrap. Kept as setters so the
     * existing two-arg construction in tests stays valid and these dependencies
     * remain optional (null-safe at the call sites).
     */
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setInvestigationRepository(InvestigationRepository investigations) {
        this.investigations = investigations;
    }

    /**
     * Validate, mark SUBMITTED, persist, run the fraud engine, store the
     * analysis, raise notifications for high-risk results, and return the
     * created claim with its analysis attached.
     */
    public ClaimResponse submitClaim(SubmitClaimRequest req, Principal principal) {
        validation.validate(req);
        Claim claim = new Claim(
                req.claimId, req.patientId, req.providerId, req.hospitalId,
                req.diagnosis, req.procedure, req.admissionDate, req.dischargeDate, req.claimDate,
                req.medicineCost, req.roomCharges, req.doctorCharges, req.labCharges, req.otherCharges,
                req.totalBilledAmount, req.insuranceAmount, STATUS_SUBMITTED, Instant.now());
        claims.save(claim);

        // Scoring runs after persistence so the claim is part of its own
        // historical context handling (the scorers exclude it where appropriate).
        FraudAnalysis analysis = fraudRiskService.analyze(claim);
        analyses.save(analysis);

        // In-app notification for high-risk results (no external delivery).
        if (notificationService != null) {
            notificationService.onClaimScored(claim, analysis);
        }
        return ClaimResponse.of(claim, analysis);
    }

    /**
     * Correct an existing claim (PRD 5.4 "Correcting claim information").
     * Only allowed while the claim is SUBMITTED or DOCUMENTS_REQUESTED. The
     * corrected claim is re-validated, re-scored, and returns to SUBMITTED.
     */
    public ClaimResponse correctClaim(String claimId, SubmitClaimRequest req) {
        Claim existing = claims.findById(claimId)
                .orElseThrow(() -> new ApiException(404, "Claim '" + claimId + "' not found"));
        if (!CORRECTABLE.contains(existing.getClaimStatus())) {
            throw new ApiException(409, "Claim '" + claimId + "' is " + existing.getClaimStatus()
                    + " and can no longer be corrected (allowed: " + CORRECTABLE + ")");
        }
        if (req.claimId != null && !req.claimId.isBlank() && !req.claimId.equals(claimId)) {
            throw new ApiException(400, "claimId in body does not match the URL");
        }
        SubmitClaimRequest fixed = new SubmitClaimRequest(claimId, req.patientId, req.providerId,
                req.hospitalId, req.diagnosis, req.procedure, req.admissionDate, req.dischargeDate,
                req.claimDate, req.medicineCost, req.roomCharges, req.doctorCharges, req.labCharges,
                req.otherCharges, req.totalBilledAmount, req.insuranceAmount);
        validation.validate(fixed, true);
        Claim corrected = new Claim(
                claimId, fixed.patientId, fixed.providerId, fixed.hospitalId,
                fixed.diagnosis, fixed.procedure, fixed.admissionDate, fixed.dischargeDate, fixed.claimDate,
                fixed.medicineCost, fixed.roomCharges, fixed.doctorCharges, fixed.labCharges,
                fixed.otherCharges, fixed.totalBilledAmount, fixed.insuranceAmount,
                STATUS_SUBMITTED, existing.getCreatedAt());
        claims.save(corrected);
        FraudAnalysis analysis = fraudRiskService.analyze(corrected);
        analyses.findByClaimId(claimId).ifPresent(old -> analyses.deleteById(old.getAnalysisId()));
        analyses.save(analysis);
        if (notificationService != null) {
            notificationService.onClaimScored(corrected, analysis);
        }
        return ClaimResponse.of(corrected, analysis);
    }

    /**
     * Claim-officer review (PRD 5.2): APPROVE a normal claim, REJECT it, or
     * ESCALATE it to the investigators (which raises an investigator notification).
     */
    public ClaimResponse review(String claimId, String action, String notes) {
        Claim existing = claims.findById(claimId)
                .orElseThrow(() -> new ApiException(404, "Claim '" + claimId + "' not found"));
        String a = action == null ? "" : action.trim().toUpperCase();
        String status;
        switch (a) {
            case "APPROVE":
                status = STATUS_APPROVED;
                break;
            case "REJECT":
                status = STATUS_REJECTED;
                break;
            case "ESCALATE":
                status = STATUS_ESCALATED;
                break;
            default:
                throw new ApiException(400, "Invalid action '" + action + "'. Allowed: APPROVE, REJECT, ESCALATE");
        }
        Claim updated = existing.withStatus(status);
        claims.save(updated);
        FraudAnalysis analysis = analyses.findByClaimId(claimId).orElse(null);
        if (STATUS_ESCALATED.equals(status) && notificationService != null) {
            String msg = "Claim " + claimId + " was escalated by a claim officer"
                    + (notes == null || notes.isBlank() ? "." : ": " + notes);
            notificationService.notify(com.frauddetector.security.Role.INVESTIGATOR, "CLAIM_ESCALATED",
                    msg, claimId, analysis == null ? "MEDIUM" : analysis.getRiskLevel());
        }
        return ClaimResponse.of(updated, analysis);
    }

    /**
     * Re-run the fraud engine over every claim (oldest first) and replace the
     * stored analyses, e.g. after the model is retrained. Returns the count.
     */
    public int rescoreAll() {
        List<Claim> all = claims.findAll();
        all.sort(Comparator.comparing(Claim::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Claim c : all) {
            FraudAnalysis fresh = fraudRiskService.analyze(c);
            analyses.findByClaimId(c.getClaimId()).ifPresent(old -> analyses.deleteById(old.getAnalysisId()));
            analyses.save(fresh);
        }
        return all.size();
    }

    /** Fetch a claim by id (with its latest analysis, if any) or raise 404. */
    public ClaimResponse getClaim(String claimId) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> new ApiException(404, "Claim '" + claimId + "' not found"));
        FraudAnalysis analysis = analyses.findByClaimId(claimId).orElse(null);
        return ClaimResponse.of(claim, analysis);
    }

    /** The stored analysis for a claim, or 404 if the claim/analysis is missing. */
    public FraudAnalysis getAnalysis(String claimId) {
        if (!claims.existsById(claimId)) {
            throw new ApiException(404, "Claim '" + claimId + "' not found");
        }
        return analyses.findByClaimId(claimId)
                .orElseThrow(() -> new ApiException(404,
                        "No fraud analysis found for claim '" + claimId + "'"));
    }

    /**
     * List claims with optional in-memory filtering and pagination (PRD section
     * 21). Supported query params:
     * <ul>
     *   <li>{@code claimId}, {@code patientId}, {@code providerId},
     *       {@code hospital} (matches hospitalId), {@code diagnosis}</li>
     *   <li>{@code procedure} / {@code treatment} (either key, substring match)</li>
     *   <li>{@code dateFrom} / {@code dateTo} (inclusive, on claimDate, ISO yyyy-MM-dd)</li>
     *   <li>{@code riskLevel} (LOW|MEDIUM|HIGH|CRITICAL, from the latest analysis)</li>
     *   <li>{@code investigationStatus} (OPEN|CLOSED|NONE, from investigations)</li>
     *   <li>{@code page} (1-based, default 1), {@code size} (default 50)</li>
     * </ul>
     * Results are ordered newest-first by creation time.
     */
    public List<Map<String, Object>> listClaims(Map<String, String> filters) {
        Map<String, String> f = filters == null ? Map.of() : filters;

        List<Claim> filtered = new ArrayList<>();
        for (Claim c : claims.findAll()) {
            if (matches(c, f)) {
                filtered.add(c);
            }
        }
        filtered.sort(Comparator.comparing(Claim::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        List<Claim> page = paginate(filtered, f);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Claim c : page) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            result.add(ClaimResponse.of(c, a).toJson());
        }
        return result;
    }

    private boolean matches(Claim c, Map<String, String> f) {
        if (!eq(f.get("claimId"), c.getClaimId())) {
            return false;
        }
        if (!eq(f.get("patientId"), c.getPatientId())) {
            return false;
        }
        if (!eq(f.get("providerId"), c.getProviderId())) {
            return false;
        }
        if (!eq(f.get("hospital"), c.getHospitalId())) {
            return false;
        }
        if (!isBlank(f.get("status")) && !f.get("status").trim().equalsIgnoreCase(c.getClaimStatus())) {
            return false;
        }
        if (!contains(f.get("diagnosis"), c.getDiagnosis())) {
            return false;
        }
        String procedureFilter = f.containsKey("procedure") ? f.get("procedure") : f.get("treatment");
        if (!contains(procedureFilter, c.getProcedure())) {
            return false;
        }
        if (!withinDateRange(c, f.get("dateFrom"), f.get("dateTo"))) {
            return false;
        }
        if (!matchesRiskLevel(c, f.get("riskLevel"))) {
            return false;
        }
        if (!matchesInvestigationStatus(c, f.get("investigationStatus"))) {
            return false;
        }
        return true;
    }

    private boolean matchesRiskLevel(Claim c, String level) {
        if (isBlank(level)) {
            return true;
        }
        FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
        return a != null && level.trim().equalsIgnoreCase(a.getRiskLevel());
    }

    private boolean matchesInvestigationStatus(Claim c, String status) {
        if (isBlank(status)) {
            return true;
        }
        String want = status.trim().toUpperCase();
        if (investigations == null) {
            // Without the collaborator, only NONE can be satisfied.
            return "NONE".equals(want);
        }
        List<?> list = investigations.findByClaimId(c.getClaimId());
        if ("NONE".equals(want)) {
            return list.isEmpty();
        }
        return investigations.findByClaimId(c.getClaimId()).stream()
                .anyMatch(i -> want.equalsIgnoreCase(i.getStatus()));
    }

    private boolean withinDateRange(Claim c, String from, String to) {
        LocalDate claimDate = c.getClaimDate();
        if (claimDate == null) {
            return isBlank(from) && isBlank(to);
        }
        if (!isBlank(from)) {
            LocalDate f = parseDate(from);
            if (f != null && claimDate.isBefore(f)) {
                return false;
            }
        }
        if (!isBlank(to)) {
            LocalDate t = parseDate(to);
            if (t != null && claimDate.isAfter(t)) {
                return false;
            }
        }
        return true;
    }

    private List<Claim> paginate(List<Claim> all, Map<String, String> f) {
        int size = parsePositiveInt(f.get("size"), 50);
        int page = parsePositiveInt(f.get("page"), 1);
        if (size <= 0) {
            size = 50;
        }
        if (page <= 0) {
            page = 1;
        }
        int fromIndex = Math.min((page - 1) * size, all.size());
        int toIndex = Math.min(fromIndex + size, all.size());
        return new ArrayList<>(all.subList(fromIndex, toIndex));
    }

    /** Claims whose latest analysis is HIGH or CRITICAL (PRD section 16). */
    public List<Map<String, Object>> listHighRisk() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Claim c : claims.findAll()) {
            FraudAnalysis a = analyses.findByClaimId(c.getClaimId()).orElse(null);
            if (a != null && fraudRiskService.thresholds().isHighRisk(a.getRiskLevel())) {
                result.add(ClaimResponse.of(c, a).toJson());
            }
        }
        return result;
    }

    private static boolean eq(String filter, String actual) {
        if (isBlank(filter)) {
            return true;
        }
        return filter.trim().equals(actual);
    }

    private static boolean contains(String filter, String actual) {
        if (isBlank(filter)) {
            return true;
        }
        return actual != null && actual.toLowerCase().contains(filter.trim().toLowerCase());
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (isBlank(raw)) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
