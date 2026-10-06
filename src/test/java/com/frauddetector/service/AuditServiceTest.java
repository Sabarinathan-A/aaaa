package com.frauddetector.service;

import com.frauddetector.repository.AuditLogRepository;
import com.frauddetector.security.AuditLog;
import com.frauddetector.testkit.Assert;

import java.util.List;

/**
 * Proves an action is recorded with the acting user and the affected target,
 * and that recent entries come back newest-first. Run via {@code ./build.sh test}.
 */
public final class AuditServiceTest {

    public static void main(String[] args) throws Exception {
        testRecordsUserAndTarget();
        testRecentNewestFirst();
        System.out.println("AuditServiceTest OK");
    }

    private static void testRecordsUserAndTarget() {
        AuditService svc = new AuditService(new AuditLogRepository());
        svc.record("U-OFFICER", AuditService.ACTION_CLAIM_SUBMIT, "CLM-1", "127.0.0.1");

        List<AuditLog> entries = svc.recent();
        Assert.assertEquals(1, entries.size(), "one entry recorded");
        AuditLog e = entries.get(0);
        Assert.assertEquals("U-OFFICER", e.getUserId(), "user recorded");
        Assert.assertEquals(AuditService.ACTION_CLAIM_SUBMIT, e.getAction(), "action recorded");
        Assert.assertEquals("CLM-1", e.getTargetId(), "target recorded");
        Assert.assertNotNull(e.getTimestamp(), "timestamp stamped");
        Assert.assertNotNull(e.getId(), "id assigned");
    }

    private static void testRecentNewestFirst() throws Exception {
        AuditService svc = new AuditService(new AuditLogRepository());
        svc.record("U1", AuditService.ACTION_LOGIN, "a@x", null);
        Thread.sleep(5);
        svc.record("U2", AuditService.ACTION_CLAIM_VIEW, "CLM-2", null);

        List<AuditLog> entries = svc.recent();
        Assert.assertEquals(2, entries.size(), "two entries");
        Assert.assertEquals("U2", entries.get(0).getUserId(), "newest first");
        Assert.assertEquals("U1", entries.get(1).getUserId(), "oldest last");
    }
}
