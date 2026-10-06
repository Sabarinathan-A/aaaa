package com.frauddetector.repository;

import com.frauddetector.security.AuditLog;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** In-memory {@link AuditLog} store keyed by its id. */
public final class AuditLogRepository extends InMemoryRepository<String, AuditLog> {

    public AuditLogRepository() {
        super(AuditLog::getId);
    }

    /** Entries ordered newest-first, limited to {@code limit} rows. */
    public List<AuditLog> findRecent(int limit) {
        return findAll().stream()
                .sorted(Comparator.comparing(AuditLog::getTimestamp).reversed())
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
    }
}
