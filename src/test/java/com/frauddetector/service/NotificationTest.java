package com.frauddetector.service;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Notification;
import com.frauddetector.repository.NotificationRepository;
import com.frauddetector.security.Role;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Proves a CRITICAL claim creates an investigator-targeted notification, a LOW
 * claim does not, and markRead flips the flag. Run via {@code ./build.sh test}.
 */
public final class NotificationTest {

    public static void main(String[] args) {
        testCriticalCreatesInvestigatorNotification();
        testLowClaimCreatesNothing();
        testMarkRead();
        System.out.println("NotificationTest OK");
    }

    private static Claim claim(String id) {
        return new Claim(id, "PAT-1", "PRV-1", "HOSP-A", "Dx", "Proc",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("90"), "SUBMITTED", Instant.now());
    }

    private static FraudAnalysis analysis(String claimId, String level, double score) {
        return new FraudAnalysis("FA-" + claimId, claimId, 0.5, 0.5, 0.5, 0.5, 0.5,
                score, level, "test-model", Collections.emptyList(), Instant.now());
    }

    private static void testCriticalCreatesInvestigatorNotification() {
        NotificationRepository repo = new NotificationRepository();
        NotificationService svc = new NotificationService(repo);

        Notification created = svc.onClaimScored(claim("CLM-CRIT"), analysis("CLM-CRIT", "CRITICAL", 92.0));
        Assert.assertNotNull(created, "notification created for CRITICAL claim");
        Assert.assertEquals(Role.INVESTIGATOR, created.getTargetRole(), "targeted at INVESTIGATOR");
        Assert.assertEquals("CLM-CRIT", created.getClaimId(), "linked to the claim");
        Assert.assertFalse(created.isRead(), "starts unread");

        List<Notification> forInvestigator = svc.forRole(Role.INVESTIGATOR);
        Assert.assertEquals(1, forInvestigator.size(), "investigator sees one notification");

        List<Notification> forAdmin = svc.forRole(Role.ADMIN);
        Assert.assertEquals(0, forAdmin.size(), "admin role sees none");
    }

    private static void testLowClaimCreatesNothing() {
        NotificationRepository repo = new NotificationRepository();
        NotificationService svc = new NotificationService(repo);

        Notification created = svc.onClaimScored(claim("CLM-LOW"), analysis("CLM-LOW", "LOW", 10.0));
        Assert.assertNull(created, "no notification for a LOW claim");
        Assert.assertEquals(0, svc.forRole(Role.INVESTIGATOR).size(), "no investigator notifications");
    }

    private static void testMarkRead() {
        NotificationRepository repo = new NotificationRepository();
        NotificationService svc = new NotificationService(repo);
        Notification created = svc.onClaimScored(claim("CLM-X"), analysis("CLM-X", "CRITICAL", 95.0));

        Notification read = svc.markRead(created.getId());
        Assert.assertTrue(read.isRead(), "notification marked read");
    }
}
