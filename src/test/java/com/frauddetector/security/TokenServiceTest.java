package com.frauddetector.security;

import com.frauddetector.testkit.Assert;

/**
 * Proves an issued token verifies and parses back to the same principal, a
 * tampered token is rejected, an expired token is rejected, and a token signed
 * with a different secret is rejected. Run via {@code ./build.sh test}.
 */
public final class TokenServiceTest {

    public static void main(String[] args) {
        testIssueAndVerify();
        testTamperedRejected();
        testExpiredRejected();
        testWrongSecretRejected();
        System.out.println("TokenServiceTest OK");
    }

    private static void testIssueAndVerify() {
        TokenService svc = new TokenService("test-secret", 3600);
        String token = svc.issue("U-1", Role.CLAIM_OFFICER);
        Principal p = svc.verify(token);
        Assert.assertEquals("U-1", p.userId(), "userId round-trips");
        Assert.assertEquals(Role.CLAIM_OFFICER, p.role(), "role round-trips");
    }

    private static void testTamperedRejected() {
        TokenService svc = new TokenService("test-secret", 3600);
        String token = svc.issue("U-1", Role.ADMIN);
        // Flip the final character of the signature segment.
        char last = token.charAt(token.length() - 1);
        char replacement = (last == 'A') ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        Assert.assertThrows(TokenService.InvalidTokenException.class,
                () -> svc.verify(tampered), "tampered token rejected");
    }

    private static void testExpiredRejected() {
        TokenService svc = new TokenService("test-secret", -10); // already expired
        String token = svc.issue("U-1", Role.PROVIDER);
        Assert.assertThrows(TokenService.InvalidTokenException.class,
                () -> svc.verify(token), "expired token rejected");
    }

    private static void testWrongSecretRejected() {
        TokenService issuer = new TokenService("secret-a", 3600);
        TokenService verifier = new TokenService("secret-b", 3600);
        String token = issuer.issue("U-1", Role.INVESTIGATOR);
        Assert.assertThrows(TokenService.InvalidTokenException.class,
                () -> verifier.verify(token), "token signed with other secret rejected");
    }
}
