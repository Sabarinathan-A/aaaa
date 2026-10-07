package com.frauddetector.service;

import com.frauddetector.domain.User;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.PasswordHasher;
import com.frauddetector.security.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * User management (PRD section 5.1) and self-service profile. Passwords are
 * only ever stored as PBKDF2 hashes; responses never include hash or salt.
 */
public final class UserService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    private static final int MIN_PASSWORD = 8;

    private final UserRepository users;
    private final PasswordHasher hasher;

    public UserService(UserRepository users, PasswordHasher hasher) {
        this.users = users;
        this.hasher = hasher;
    }

    public List<Map<String, Object>> list() {
        List<User> all = users.findAll();
        all.sort(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : all) {
            out.add(toJson(u));
        }
        return out;
    }

    public Map<String, Object> get(String id) {
        return toJson(find(id));
    }

    public User find(String id) {
        return users.findById(id).orElseThrow(() -> new ApiException(404, "User '" + id + "' not found"));
    }

    /** Create a user (ADMIN). Email must be unique; password at least 8 chars. */
    public Map<String, Object> create(Map<String, Object> body) {
        String name = text(body, "name");
        String email = normalizeEmail(text(body, "email"));
        String password = text(body, "password");
        Role role = parseRole(text(body, "role"));
        if (name == null || email == null || password == null) {
            throw new ApiException(400, "name, email, password and role are required");
        }
        validatePassword(password);
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(409, "A user with email '" + email + "' already exists");
        }
        String salt = hasher.newSalt();
        User u = new User("U-" + UUID.randomUUID().toString().substring(0, 8), name, email,
                hasher.hash(password, salt), salt, role, STATUS_ACTIVE, Instant.now());
        users.save(u);
        return toJson(u);
    }

    /**
     * Update name / role / status and optionally reset the password (ADMIN).
     * An admin cannot disable or demote their own account (prevents lock-out).
     */
    public Map<String, Object> update(String id, Map<String, Object> body, String actingUserId) {
        User u = find(id);
        String name = orElse(text(body, "name"), u.getName());
        Role role = text(body, "role") == null ? u.getRole() : parseRole(text(body, "role"));
        String status = text(body, "status") == null ? u.getStatus() : parseStatus(text(body, "status"));
        if (id.equals(actingUserId) && (role != u.getRole() || STATUS_DISABLED.equals(status))) {
            throw new ApiException(400, "You cannot change your own role or disable your own account");
        }
        String hash = u.getPasswordHash();
        String salt = u.getSalt();
        String password = text(body, "password");
        if (password != null) {
            validatePassword(password);
            salt = hasher.newSalt();
            hash = hasher.hash(password, salt);
        }
        User updated = new User(u.getId(), name, u.getEmail(), hash, salt, role, status, u.getCreatedAt());
        users.save(updated);
        return toJson(updated);
    }

    /** Self-service password change; requires the current password. */
    public void changeOwnPassword(String userId, String currentPassword, String newPassword) {
        User u = find(userId);
        if (currentPassword == null || !hasher.verify(currentPassword, u.getSalt(), u.getPasswordHash())) {
            throw new ApiException(400, "Current password is incorrect");
        }
        if (newPassword == null) {
            throw new ApiException(400, "newPassword is required");
        }
        validatePassword(newPassword);
        String salt = hasher.newSalt();
        users.save(new User(u.getId(), u.getName(), u.getEmail(), hasher.hash(newPassword, salt), salt,
                u.getRole(), u.getStatus(), u.getCreatedAt()));
    }

    /** True when the user exists and is ACTIVE (used to revoke tokens of disabled users). */
    public boolean isActive(String userId) {
        return users.findById(userId).map(u -> !STATUS_DISABLED.equals(u.getStatus())).orElse(false);
    }

    public static Map<String, Object> toJson(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("role", u.getRole().name());
        m.put("status", u.getStatus());
        m.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
        return m;
    }

    private static void validatePassword(String password) {
        if (password.length() < MIN_PASSWORD) {
            throw new ApiException(400, "Password must be at least " + MIN_PASSWORD + " characters");
        }
    }

    private static Role parseRole(String raw) {
        if (raw == null) {
            throw new ApiException(400, "role is required (ADMIN, CLAIM_OFFICER, INVESTIGATOR, PROVIDER)");
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Invalid role '" + raw + "'. Allowed: ADMIN, CLAIM_OFFICER, INVESTIGATOR, PROVIDER");
        }
    }

    private static String parseStatus(String raw) {
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(s) && !STATUS_DISABLED.equals(s)) {
            throw new ApiException(400, "Invalid status '" + raw + "'. Allowed: ACTIVE, DISABLED");
        }
        return s;
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        if (!e.matches("[^@\\s]+@[^@\\s]+")) {
            throw new ApiException(400, "Invalid email address");
        }
        return e;
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String orElse(String v, String fallback) {
        return v == null ? fallback : v;
    }
}
