package com.frauddetector.repository;

import com.frauddetector.domain.Evidence;

import java.util.Comparator;
import java.util.List;

/** In-memory store for {@link Evidence} metadata, keyed by evidenceId. */
public final class EvidenceRepository extends InMemoryRepository<String, Evidence> {

    public EvidenceRepository() {
        super(Evidence::getEvidenceId);
    }

    public List<Evidence> findByInvestigationId(String investigationId) {
        return findAll().stream()
                .filter(e -> investigationId.equals(e.getInvestigationId()))
                .sorted(Comparator.comparing(Evidence::getUploadedAt))
                .toList();
    }
}
