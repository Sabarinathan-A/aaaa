package com.frauddetector.security;

import com.frauddetector.testkit.Assert;

/**
 * Proves PBKDF2 hashing verifies true for the correct password and false for a
 * wrong one, and that two salts for the same password differ. Run via
 * {@code ./build.sh test}.
 */
public final class PasswordHasherTest {

    public static void main(String[] args) {
        testVerifyCorrect();
        testRejectWrong();
        testDistinctSalts();
        System.out.println("PasswordHasherTest OK");
    }

    private static void testVerifyCorrect() {
        PasswordHasher hasher = new PasswordHasher();
        String salt = hasher.newSalt();
        String hash = hasher.hash("s3cret!", salt);
        Assert.assertTrue(hasher.verify("s3cret!", salt, hash), "correct password verifies");
    }

    private static void testRejectWrong() {
        PasswordHasher hasher = new PasswordHasher();
        String salt = hasher.newSalt();
        String hash = hasher.hash("s3cret!", salt);
        Assert.assertFalse(hasher.verify("wrong", salt, hash), "wrong password rejected");
    }

    private static void testDistinctSalts() {
        PasswordHasher hasher = new PasswordHasher();
        String salt1 = hasher.newSalt();
        String salt2 = hasher.newSalt();
        Assert.assertFalse(salt1.equals(salt2), "salts are random/distinct");
        String h1 = hasher.hash("same", salt1);
        String h2 = hasher.hash("same", salt2);
        Assert.assertFalse(h1.equals(h2), "same password under different salts yields different hashes");
    }
}
