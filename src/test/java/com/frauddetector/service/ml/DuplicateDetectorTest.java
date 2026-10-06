package com.frauddetector.service.ml;

import com.frauddetector.domain.Claim;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.service.ml.DuplicateDetector.Result;
import com.frauddetector.testkit.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Proves the duplicate detector (PRD section 12) flags a claim with the same
 * patient + provider + date + amount as a duplicate and does not flag a
 * dissimilar claim. Run via {@code ./build.sh test}.
 */
public final class DuplicateDetectorTest {

    public static void main(String[] args) {
        testIdenticalClaimIsDuplicate();
        testDissimilarClaimIsNotDuplicate();
        System.out.println("DuplicateDetectorTest OK");
    }

    private static Claim claim(String id, String patient, String provider, LocalDate date,
                               String diagnosis, String procedure, String total) {
        return new Claim(id, patient, provider, "HOSP-A", diagnosis, procedure,
                date, date, date,
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("50"), new BigDecimal("50"), new BigDecimal(total),
                new BigDecimal(total), "SUBMITTED", Instant.now());
    }

    private static void testIdenticalClaimIsDuplicate() {
        DuplicateDetector detector = new DuplicateDetector(new ClaimRepository());
        Claim original = claim("CLM-1", "PAT-1", "PRV-1", LocalDate.of(2024, 5, 1),
                "Fracture", "X-Ray", "2000");
        // Same patient, provider, date, amount, diagnosis, procedure.
        Claim resubmitted = claim("CLM-2", "PAT-1", "PRV-1", LocalDate.of(2024, 5, 1),
                "Fracture", "X-Ray", "2000");

        Result r = detector.detect(resubmitted, List.of(original));
        Assert.assertTrue(r.similarity >= 0.85,
                "an identical claim is flagged as a duplicate (similarity=" + r.similarity + ")");
        Assert.assertEquals("CLM-1", r.mostSimilarClaimId, "most similar claim is the original");
    }

    private static void testDissimilarClaimIsNotDuplicate() {
        DuplicateDetector detector = new DuplicateDetector(new ClaimRepository());
        Claim original = claim("CLM-1", "PAT-1", "PRV-1", LocalDate.of(2024, 5, 1),
                "Fracture", "X-Ray", "2000");
        // Different patient, provider, date (months apart), amount, diagnosis, procedure.
        Claim unrelated = claim("CLM-9", "PAT-9", "PRV-9", LocalDate.of(2024, 11, 20),
                "Flu", "Consultation", "250");

        Result r = detector.detect(unrelated, List.of(original));
        Assert.assertTrue(r.similarity < 0.85,
                "a dissimilar claim is not flagged as a duplicate (similarity=" + r.similarity + ")");
    }
}
