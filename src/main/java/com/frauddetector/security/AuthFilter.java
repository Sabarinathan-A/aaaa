package com.frauddetector.security;

import com.frauddetector.http.ApiException;
import com.frauddetector.http.HttpContext;

/**
 * Authentication helper for route handlers. Reads the
 * {@code Authorization: Bearer <token>} header, verifies it with
 * {@link TokenService}, and sets the {@link Principal} on the
 * {@link HttpContext}. {@link #requireRole} enforces role-based access:
 * <ul>
 *   <li>missing/invalid token -&gt; {@link ApiException} 401</li>
 *   <li>authenticated but wrong role -&gt; {@link ApiException} 403</li>
 * </ul>
 */
public final class AuthFilter {

    private final TokenService tokenService;

    public AuthFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Authenticate the request, store the principal on the context, and return it.
     *
     * @throws ApiException 401 when the Authorization header is missing/invalid
     */
    public Principal authenticate(HttpContext ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(401, "Missing or malformed Authorization header");
        }
        String token = header.substring(7).trim();
        try {
            Principal principal = tokenService.verify(token);
            ctx.setPrincipal(principal);
            return principal;
        } catch (TokenService.InvalidTokenException e) {
            throw new ApiException(401, "Invalid or expired token");
        }
    }

    /**
     * Authenticate and require that the caller holds one of {@code allowed}.
     *
     * @throws ApiException 401 when unauthenticated, 403 when the role is not permitted
     */
    public Principal requireRole(HttpContext ctx, Role... allowed) {
        Principal principal = authenticate(ctx);
        for (Role role : allowed) {
            if (principal.role() == role) {
                return principal;
            }
        }
        throw new ApiException(403, "Role " + principal.role() + " is not permitted to access this resource");
    }
}
