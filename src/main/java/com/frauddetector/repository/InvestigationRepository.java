package com.frauddetector.repository;

import com.frauddetector.domain.Investigation;

import java.util.List;
import java.util.stream.Collectors;

/** In-memory {@link Investigation} store keyed by investigationId. */
public final class InvestigationRepository extends InMemoryRepository<String, Investigation> {

    public InvestigationRepository() {
        super(Investigation::getInvestigationId);
    }

    public List<Investigation> findByClaimId(String claimId) {
        return findAll().stream()
                .filter(i -> i.getClaimId().equals(claimId))
                .collect(Collectors.toList());
    }
}
