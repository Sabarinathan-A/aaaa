package com.frauddetector.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.frauddetector.http.Json;

/**
 * Issues and verifies a hand-rolled, JWT-shaped stateless token. The format is
 * {@code base64url(header).base64url(payload).base64url(signature)} where the
 * signature is {@code HmacSHA256(header.payload, secret)} via {@link Mac}.
 *
 * <p>The payload carries {@code userId}, {@code role}, and {@code exp} (epoch
 * seconds). No external JWT library is used.
 */
public final class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(String secret, long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** Issue a signed token for the given user/role, expiring after the configured TTL. */
    public String issue(String userId, Role role) {
        long exp = nowSeconds() + ttlSeconds;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("role", role.name());
        payload.put("exp", exp);

        String headerSeg = URL_ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String payloadSeg = URL_ENCODER.encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerSeg + "." + payloadSeg;
        String signatureSeg = URL_ENCODER.encodeToString(sign(signingInput));
        return signingInput + "." + signatureSeg;
    }

    /**
     * Verify the signature and expiry, returning the {@link Principal} it carries.
     *
     * @throws InvalidTokenException if the token is malformed, tampered, or expired
     */
    public Principal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("missing token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed token");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSig = sign(signingInput);
        byte[] actualSig;
        try {
            actualSig = URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("bad signature encoding");
        }
        if (!constantTimeEquals(expectedSig, actualSig)) {
            throw new InvalidTokenException("signature mismatch");
        }

        Map<String, Object> payload;
        try {
            String payloadJson = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            payload = Json.parseObject(payloadJson);
        } catch (RuntimeException e) {
            throw new InvalidTokenException("bad payload");
        }

        Object exp = payload.get("exp");
        if (!(exp instanceof Number)) {
            throw new InvalidTokenException("missing expiry");
        }
        if (((Number) exp).longValue() < nowSeconds()) {
            throw new InvalidTokenException("token expired");
        }

        Object userId = payload.get("userId");
        Object role = payload.get("role");
        if (userId == null || role == null) {
            throw new InvalidTokenException("missing claims");
        }
        Role parsedRole;
        try {
            parsedRole = Role.valueOf(role.toString());
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("unknown role");
        }
        return new Principal(userId.toString(), parsedRole);
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /** Thrown when a token cannot be verified. Mapped to HTTP 401 by {@link AuthFilter}. */
    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
