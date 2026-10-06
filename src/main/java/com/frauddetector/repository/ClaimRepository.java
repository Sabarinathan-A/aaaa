package com.frauddetector.repository;

import com.frauddetector.domain.Claim;

import java.util.List;
import java.util.stream.Collectors;

/** In-memory {@link Claim} store keyed by claimId, with provider/patient lookups. */
public final class ClaimRepository extends InMemoryRepository<String, Claim> {

    public ClaimRepository() {
        super(Claim::getClaimId);
    }

    public List<Claim> findByPatientId(String patientId) {
        return findAll().stream()
                .filter(c -> c.getPatientId().equals(patientId))
                .collect(Collectors.toList());
    }

    public List<Claim> findByProviderId(String providerId) {
        return findAll().stream()
                .filter(c -> c.getProviderId().equals(providerId))
                .collect(Collectors.toList());
    }
}
