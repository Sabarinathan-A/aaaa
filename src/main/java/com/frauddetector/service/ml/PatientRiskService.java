package com.frauddetector.service.ml;

import com.frauddetector.domain.Claim;
import com.frauddetector.repository.ClaimRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Patient-level behavior analysis (PRD section 14). Derives a patient risk score
 * in [0,1] from:
 *
 * <ul>
 *   <li>claim frequency inside a recent window (bursts of claims),</li>
 *   <li>repeated identical treatments (same diagnosis+procedure filed more than
 *       once),</li>
 *   <li>the same treatment appearing across multiple hospitals (hospital
 *       hopping).</li>
 * </ul>
 */
public final class PatientRiskService {

    private static final double W_FREQUENCY = 0.40;
    private static final double W_REPEATED = 0.30;
    private static final double W_MULTI_HOSPITAL = 0.30;

    // Window (days) and the count at which frequency saturates to 1.0.
    private static final long WINDOW_DAYS = 90;
    private static final double FREQUENCY_SATURATION = 5.0;

    private final ClaimRepository claims;

    public PatientRiskService(ClaimRepository claims) {
        this.claims = claims;
    }

    /** Result of a patient-risk computation. */
    public static final class Result {
        public final double riskScore;             // [0,1]
        public final int claimsInWindow;
        public final int repeatedTreatments;
        public final int maxHospitalsForOneTreatment;

        Result(double riskScore, int claimsInWindow, int repeatedTreatments,
               int maxHospitalsForOneTreatment) {
            this.riskScore = riskScore;
            this.claimsInWindow = claimsInWindow;
            this.repeatedTreatments = repeatedTreatments;
            this.maxHospitalsForOneTreatment = maxHospitalsForOneTreatment;
        }
    }

    public Result assess(Claim claim) {
        List<Claim> history = claims.findByPatientId(claim.getPatientId());

        LocalDate reference = claim.getClaimDate() != null ? claim.getClaimDate() : LocalDate.now();
        int inWindow = 0;
        for (Claim c : history) {
            LocalDate d = c.getClaimDate() != null ? c.getClaimDate() : c.getAdmissionDate();
            if (d == null) {
                continue;
            }
            long gap = Math.abs(ChronoUnit.DAYS.between(d, reference));
            if (gap <= WINDOW_DAYS) {
                inWindow++;
            }
        }
        double frequencyFactor = clamp01(inWindow / FREQUENCY_SATURATION);

        // Repeated treatments: count claims sharing this claim's diagnosis+procedure.
        int repeated = 0;
        Set<String> hospitalsForThisTreatment = new HashSet<>();
        for (Claim c : history) {
            if (sameTreatment(c, claim)) {
                repeated++;
                if (c.getHospitalId() != null) {
                    hospitalsForThisTreatment.add(c.getHospitalId());
                }
            }
        }
        // repeated includes the claim itself when it is already persisted; a value
        // of >=2 means a genuine repeat.
        double repeatedFactor = clamp01((repeated - 1) / 3.0);

        int multiHospital = hospitalsForThisTreatment.size();
        double multiHospitalFactor = multiHospital <= 1 ? 0.0 : clamp01((multiHospital - 1) / 2.0);

        double risk = clamp01(
                W_FREQUENCY * frequencyFactor
                        + W_REPEATED * repeatedFactor
                        + W_MULTI_HOSPITAL * multiHospitalFactor);

        return new Result(risk, inWindow, Math.max(0, repeated), multiHospital);
    }

    private static boolean sameTreatment(Claim a, Claim b) {
        return equalsIgnoreCaseSafe(a.getDiagnosis(), b.getDiagnosis())
                && equalsIgnoreCaseSafe(a.getProcedure(), b.getProcedure());
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
