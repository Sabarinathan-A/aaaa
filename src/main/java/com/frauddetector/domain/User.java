package com.frauddetector.domain;

import com.frauddetector.security.Role;

import java.time.Instant;

/**
 * An application user (PRD sections 23-24). The password is stored only as a
 * PBKDF2 hash plus its salt; the plaintext is never persisted.
 */
public final class User {

    private final String id;
    private final String name;
    private final String email;
    private final String passwordHash;
    private final String salt;
    private final Role role;
    private final String status;
    private final Instant createdAt;

    public User(String id, String name, String email, String passwordHash, String salt,
                Role role, String status, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public Role getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
