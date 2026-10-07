package com.frauddetector.service;

import com.frauddetector.config.ThresholdConfig;
import com.frauddetector.domain.Investigation;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.dto.SubmitClaimRequest;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.NotificationRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.security.Principal;
import com.frauddetector.security.Role;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * End-to-end claim workflow at the service layer: investigation decisions
 * drive the claim status, investigations can be listed/filtered, claims can
 * only be corrected while SUBMITTED / DOCUMENTS_REQUESTED, and an officer
 * escalation notifies investigators. Run via {@code ./build.sh test}.
 */
public final class WorkflowTest {

    private static ClaimRepository claims;
    private static InvestigationRepository investigations;
    private static NotificationRepository notifications;
    private static ClaimService claimService;
    private static InvestigationService investigationService;

    public static void main(String[] args) {
        testDecisionDrivesClaimStatus();
        testListFilters();
        testCorrectionRules();
        testOfficerEscalationNotifies();
        System.out.println("WorkflowTest OK");
    }

    private static void setUp() {
        claims = new ClaimRepository();
        investigations = new InvestigationRepository();
        notifications = new NotificationRepository();
        PatientRepository patients = new PatientRepository();
        ProviderRepository providers = new ProviderRepository();
        patients.save(new Patient("PAT-1", "Pat One", 40, "F", "Austin", "INS-1"));
        providers.save(new Provider("PRV-1", "Prov", "Hosp", "Austin", "Cardio", BigDecimal.ZERO));
        FraudAnalysisRepository analyses = new FraudAnalysisRepository();
        claimService = new ClaimService(claims, new ValidationService(patients, providers, claims),
                new FraudRiskService(claims, patients, analyses, ThresholdConfig.defaults()), analyses);
        claimService.setNotificationService(new NotificationService(notifications));
        claimService.setInvestigationRepository(investigations);
        investigationService = new InvestigationService(investigations, claims);
    }

    private static SubmitClaimRequest req(String id, String total) {
        BigDecimal t = new BigDecimal(total);
        return new SubmitClaimRequest(id, "PAT-1", "PRV-1", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 11), LocalDate.of(2025, 1, 12),
                t, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, t, t);
    }

    private static String status(String claimId) {
        return claims.findById(claimId).orElseThrow().getClaimStatus();
    }

    private static void testDecisionDrivesClaimStatus() {
        setUp();
        Principal inv = new Principal("U-INV", Role.INVESTIGATOR);
        claimService.submitClaim(req("CLM-1", "1000"), inv);
        Assert.assertEquals("SUBMITTED", status("CLM-1"), "new claim is SUBMITTED");

        Investigation i = investigationService.open("CLM-1", "check", inv);
        Assert.assertEquals("UNDER_INVESTIGATION", status("CLM-1"), "opening moves claim under investigation");

        investigationService.decide(i.getInvestigationId(), "REQUEST_DOCUMENTS", "need invoice");
        Assert.assertEquals("DOCUMENTS_REQUESTED", status("CLM-1"), "request documents");
        Assert.assertEquals("OPEN", investigationService.get(i.getInvestigationId()).getStatus(), "still open");

        investigationService.decide(i.getInvestigationId(), "REJECT", "fraud confirmed");
        Assert.assertEquals("REJECTED", status("CLM-1"), "reject closes claim as REJECTED");
        Assert.assertEquals("CLOSED", investigationService.get(i.getInvestigationId()).getStatus(), "closed");

        Assert.assertEquals("APPROVED", InvestigationService.claimStatusFor(Investigation.Decision.FALSE_POSITIVE), "false positive approves");
        Assert.assertEquals("SUSPICIOUS", InvestigationService.claimStatusFor(Investigation.Decision.MARK_SUSPICIOUS), "suspicious");
    }

    private static void testListFilters() {
        setUp();
        Principal inv = new Principal("U-INV", Role.INVESTIGATOR);
        claimService.submitClaim(req("CLM-A", "1000"), inv);
        claimService.submitClaim(req("CLM-B", "1200"), inv);
        Investigation a = investigationService.open("CLM-A", null, inv);
        investigationService.open("CLM-B", null, inv);
        investigationService.decide(a.getInvestigationId(), "APPROVE", null);
        Assert.assertEquals(2, investigationService.list(Map.of()).size(), "all");
        Assert.assertEquals(1, investigationService.list(Map.of("status", "OPEN")).size(), "open only");
        Assert.assertEquals("CLM-B", investigationService.list(Map.of("status", "OPEN")).get(0).getClaimId(), "open is B");
        Assert.assertEquals(1, investigationService.list(Map.of("decision", "approve")).size(), "by decision");
        Assert.assertEquals(1, claimService.listClaims(Map.of("status", "APPROVED")).size(), "claim status filter");
    }

    private static void testCorrectionRules() {
        setUp();
        Principal provider = new Principal("U-PRV", Role.PROVIDER);
        claimService.submitClaim(req("CLM-C", "1000"), provider);
        var corrected = claimService.correctClaim("CLM-C", req("CLM-C", "1500"));
        Assert.assertEquals("1500", corrected.toJson().get("totalBilledAmount"), "amount corrected");
        Assert.assertNotNull(corrected.toJson().get("fraudAnalysis"), "re-scored");

        try {
            claimService.correctClaim("CLM-C", req("CLM-OTHER", "1500"));
            throw new AssertionError("mismatched id should fail");
        } catch (ApiException e) {
            Assert.assertEquals(400, e.getStatusCode(), "id mismatch is 400");
        }

        claimService.review("CLM-C", "APPROVE", null);
        try {
            claimService.correctClaim("CLM-C", req("CLM-C", "900"));
            throw new AssertionError("approved claim must not be correctable");
        } catch (ApiException e) {
            Assert.assertEquals(409, e.getStatusCode(), "approved claim is 409");
        }
        try {
            claimService.correctClaim("CLM-NOPE", req("CLM-NOPE", "900"));
            throw new AssertionError("unknown claim");
        } catch (ApiException e) {
            Assert.assertEquals(404, e.getStatusCode(), "unknown claim is 404");
        }
    }

    private static void testOfficerEscalationNotifies() {
        setUp();
        Principal officer = new Principal("U-OFF", Role.CLAIM_OFFICER);
        claimService.submitClaim(req("CLM-E", "1000"), officer);
        int before = notifications.findByRole(Role.INVESTIGATOR).size();
        claimService.review("CLM-E", "ESCALATE", "looks odd");
        Assert.assertEquals("ESCALATED", status("CLM-E"), "escalated status");
        Assert.assertEquals(before + 1, notifications.findByRole(Role.INVESTIGATOR).size(), "investigators notified");
        try {
            claimService.review("CLM-E", "MAYBE", null);
            throw new AssertionError("invalid action");
        } catch (ApiException e) {
            Assert.assertEquals(400, e.getStatusCode(), "invalid action 400");
        }
    }
}
