package com.frauddetector.service;

import com.frauddetector.domain.Patient;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.DataMasker;
import com.frauddetector.security.PasswordHasher;
import com.frauddetector.security.Role;
import com.frauddetector.security.TokenService;
import com.frauddetector.testkit.Assert;

import java.util.HashMap;
import java.util.Map;

/**
 * User management (create, unique email, password policy, disable, no
 * self-lockout, password change), login blocked for disabled accounts, and
 * role-based masking of patient identity. Run via {@code ./build.sh test}.
 */
public final class UserManagementTest {

    public static void main(String[] args) {
        testUserLifecycle();
        testMasking();
        System.out.println("UserManagementTest OK");
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void testUserLifecycle() {
        UserRepository users = new UserRepository();
        PasswordHasher hasher = new PasswordHasher();
        UserService svc = new UserService(users, hasher);
        AuthService auth = new AuthService(users, hasher, new TokenService("test-secret", 3600));

        Map<String, Object> admin = svc.create(body("name", "Root", "email", "Root@X.io", "password", "rootpass1", "role", "ADMIN"));
        Map<String, Object> u = svc.create(body("name", "Ivy", "email", "ivy@x.io", "password", "secret123", "role", "investigator"));
        Assert.assertEquals("root@x.io", admin.get("email"), "email normalized");
        Assert.assertEquals("INVESTIGATOR", u.get("role"), "role parsed case-insensitively");
        Assert.assertFalse(u.containsKey("passwordHash"), "hash never exposed");
        Assert.assertNotNull(auth.login("ivy@x.io", "secret123").token(), "new user can log in");

        expect(409, () -> svc.create(body("name", "Dup", "email", "IVY@x.io", "password", "secret123", "role", "ADMIN")));
        expect(400, () -> svc.create(body("name", "Short", "email", "s@x.io", "password", "short", "role", "ADMIN")));
        expect(400, () -> svc.create(body("name", "Bad", "email", "b@x.io", "password", "secret123", "role", "GOD")));

        String id = (String) u.get("id");
        String adminId = (String) admin.get("id");
        svc.update(id, body("status", "DISABLED"), adminId);
        Assert.assertFalse(svc.isActive(id), "disabled user inactive");
        expect(403, () -> auth.login("ivy@x.io", "secret123"));
        expect(400, () -> svc.update(adminId, body("status", "DISABLED"), adminId));
        expect(400, () -> svc.update(adminId, body("role", "PROVIDER"), adminId));

        svc.update(id, body("status", "ACTIVE", "password", "newsecret9"), adminId);
        Assert.assertNotNull(auth.login("ivy@x.io", "newsecret9").token(), "admin password reset works");
        expect(400, () -> svc.changeOwnPassword(id, "wrong-current", "another123"));
        svc.changeOwnPassword(id, "newsecret9", "another123");
        Assert.assertNotNull(auth.login("ivy@x.io", "another123").token(), "self-service change works");
    }

    private static void testMasking() {
        Assert.assertEquals("J*** D**", DataMasker.maskName("John Doe"), "name mask");
        Assert.assertEquals("****1001", DataMasker.maskId("INS-1001"), "id mask");
        Assert.assertTrue(DataMasker.canViewSensitive(Role.INVESTIGATOR), "investigator sees full data");
        Assert.assertFalse(DataMasker.canViewSensitive(Role.PROVIDER), "provider sees masked data");

        PatientRepository patients = new PatientRepository();
        patients.save(new Patient("PAT-1", "John Doe", 54, "M", "Austin", "INS-1001"));
        PatientService svc = new PatientService(patients, new ClaimRepository(), new FraudAnalysisRepository());
        Assert.assertEquals("J*** D**", svc.get("PAT-1", Role.PROVIDER).get("name"), "provider gets masked name");
        Assert.assertEquals("****1001", svc.get("PAT-1", Role.PROVIDER).get("insuranceId"), "provider gets masked insurance id");
        Assert.assertEquals("John Doe", svc.get("PAT-1", Role.CLAIM_OFFICER).get("name"), "officer gets full name");
        Assert.assertEquals(0, svc.list(Role.PROVIDER, "john").size(), "provider cannot search by hidden name");
        Assert.assertEquals(1, svc.list(Role.ADMIN, "john").size(), "admin can search by name");
    }

    private static void expect(int status, Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected ApiException " + status);
        } catch (ApiException e) {
            Assert.assertEquals(status, e.getStatusCode(), "status for: " + e.getMessage());
        }
    }
}
