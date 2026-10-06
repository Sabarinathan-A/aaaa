package com.frauddetector.repository;

import com.frauddetector.domain.FraudAnalysis;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** In-memory {@link FraudAnalysis} store keyed by analysisId, with claim lookup. */
public final class FraudAnalysisRepository extends InMemoryRepository<String, FraudAnalysis> {

    public FraudAnalysisRepository() {
        super(FraudAnalysis::getAnalysisId);
    }

    /** The most recent analysis for a claim, if any. */
    public Optional<FraudAnalysis> findByClaimId(String claimId) {
        return findAll().stream()
                .filter(a -> a.getClaimId().equals(claimId))
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
    }

    /** All analyses whose risk level is one of {@code levels}. */
    public List<FraudAnalysis> findByRiskLevels(String... levels) {
        return findAll().stream()
                .filter(a -> {
                    for (String level : levels) {
                        if (level.equals(a.getRiskLevel())) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }
}
