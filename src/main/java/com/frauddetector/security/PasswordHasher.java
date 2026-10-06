package com.frauddetector.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing using the JDK only (PRD FR-01). Uses
 * {@code PBKDF2WithHmacSHA256} via {@link SecretKeyFactory} with a per-user
 * random salt. The salt and derived hash are stored separately; verification
 * recomputes the hash with the stored salt and compares in constant time.
 *
 * <p>No external library (BCrypt/Argon2) is available in this sandbox, so
 * PBKDF2 from {@code javax.crypto} is used instead.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    /** A freshly generated random salt, Base64-encoded. */
    public String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** Derive a Base64-encoded PBKDF2 hash of {@code password} with the given Base64 salt. */
    public String hash(String password, String base64Salt) {
        byte[] salt = Base64.getDecoder().decode(base64Salt);
        byte[] derived = pbkdf2(password.toCharArray(), salt);
        return Base64.getEncoder().encodeToString(derived);
    }

    /** True when {@code password} re-derives to the stored hash under the stored salt. */
    public boolean verify(String password, String base64Salt, String expectedBase64Hash) {
        if (password == null || base64Salt == null || expectedBase64Hash == null) {
            return false;
        }
        String actual = hash(password, base64Salt);
        return constantTimeEquals(actual, expectedBase64Hash);
    }

    private byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("Invalid PBKDF2 key spec", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("PBKDF2 algorithm unavailable: " + ALGORITHM, e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = ba.length ^ bb.length;
        for (int i = 0; i < ba.length && i < bb.length; i++) {
            diff |= ba[i] ^ bb[i];
        }
        return diff == 0;
    }
}
