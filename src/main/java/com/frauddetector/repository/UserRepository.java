package com.frauddetector.repository;

import com.frauddetector.domain.User;

import java.util.Optional;

/** In-memory {@link User} store keyed by user id, with lookup by email. */
public final class UserRepository extends InMemoryRepository<String, User> {

    public UserRepository() {
        super(User::getId);
    }

    public Optional<User> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return findAll().stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .findFirst();
    }
}
