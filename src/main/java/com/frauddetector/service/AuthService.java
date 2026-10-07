package com.frauddetector.service;

import com.frauddetector.domain.User;
import com.frauddetector.dto.LoginResponse;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.PasswordHasher;
import com.frauddetector.security.TokenService;

import java.util.Optional;

/**
 * Authentication service: verifies credentials and issues a signed token.
 */
public final class AuthService {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenService tokens;

    public AuthService(UserRepository users, PasswordHasher hasher, TokenService tokens) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
    }

    /**
     * Verify the password for {@code email} and issue a token.
     *
     * @throws ApiException 401 when the email is unknown or the password is wrong
     */
    public LoginResponse login(String email, String password) {
        if (email == null || password == null) {
            throw new ApiException(401, "Invalid credentials");
        }
        Optional<User> maybe = users.findByEmail(email);
        if (maybe.isEmpty()) {
            throw new ApiException(401, "Invalid credentials");
        }
        User user = maybe.get();
        if (!hasher.verify(password, user.getSalt(), user.getPasswordHash())) {
            throw new ApiException(401, "Invalid credentials");
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new ApiException(403, "This account is disabled");
        }
        String token = tokens.issue(user.getId(), user.getRole());
        return new LoginResponse(token, user.getRole().name(), user.getName());
    }
}
