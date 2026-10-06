package com.frauddetector.repository;

import com.frauddetector.domain.Notification;
import com.frauddetector.security.Role;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** In-memory {@link Notification} store keyed by its id. */
public final class NotificationRepository extends InMemoryRepository<String, Notification> {

    public NotificationRepository() {
        super(Notification::getId);
    }

    /** Notifications targeted at {@code role}, newest-first. */
    public List<Notification> findByRole(Role role) {
        return findAll().stream()
                .filter(n -> n.getTargetRole() == role)
                .sorted(Comparator.comparing(Notification::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}
