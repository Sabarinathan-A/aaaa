package com.frauddetector.security;

/**
 * The authenticated caller derived from a verified bearer token. Stored on the
 * {@link com.frauddetector.http.HttpContext} by {@link AuthFilter}.
 */
public record Principal(String userId, Role role) {
}
