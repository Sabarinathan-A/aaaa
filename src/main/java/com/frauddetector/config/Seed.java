package com.frauddetector.config;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.domain.User;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.PasswordHasher;
import com.frauddetector.security.Role;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Seeds the in-memory stores at startup: one user per role (with known
 * credentials printed to stdout for manual testing), a handful of patients and
 * providers, and a few baseline historical claims so later ML features have
 * reference data.
 */
public final class Seed {

    /** A seeded login credential, used only for the startup banner. */
    public record Credential(String role, String email, String password) {
    }

    private final UserRepository users;
    private final PatientRepository patients;
    private final ProviderRepository providers;
    private final ClaimRepository claims;
    private final PasswordHasher hasher;

    public Seed(UserRepository users, PatientRepository patients, ProviderRepository providers,
                ClaimRepository claims, PasswordHasher hasher) {
        this.users = users;
        this.patients = patients;
        this.providers = providers;
        this.claims = claims;
        this.hasher = hasher;
    }

    /** Populate all stores. Returns the seeded login credentials for display. */
    public java.util.List<Credential> load() {
        java.util.List<Credential> credentials = new java.util.ArrayList<>();
        credentials.add(seedUser("U-ADMIN", "Alice Admin", "admin@fraud.local", "admin123", Role.ADMIN));
        credentials.add(seedUser("U-OFFICER", "Olivia Officer", "officer@fraud.local", "officer123", Role.CLAIM_OFFICER));
        credentials.add(seedUser("U-INVESTIGATOR", "Ian Investigator", "investigator@fraud.local", "investigate123", Role.INVESTIGATOR));
        credentials.add(seedUser("U-PROVIDER", "Pat Provider", "provider@fraud.local", "provider123", Role.PROVIDER));

        patients.save(new Patient("PAT-001", "John Doe", 54, "M", "Austin", "INS-1001"));
        patients.save(new Patient("PAT-002", "Jane Roe", 37, "F", "Dallas", "INS-1002"));
        patients.save(new Patient("PAT-003", "Sam Lee", 29, "M", "Houston", "INS-1003"));

        providers.save(new Provider("PRV-001", "Pat Provider", "St. Mary Hospital", "Austin", "Cardiology", new BigDecimal("0.10")));
        providers.save(new Provider("PRV-002", "City Clinic", "City Clinic", "Dallas", "Orthopedics", new BigDecimal("0.25")));
        providers.save(new Provider("PRV-003", "Metro Health", "Metro Health", "Houston", "General", new BigDecimal("0.15")));

        seedClaim("CLM-H001", "PAT-001", "PRV-001", "HOSP-A", "Chest pain", "ECG",
                LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 13), LocalDate.of(2024, 1, 15),
                "500", "1200", "800", "300", "200", "3000", "2500");
        seedClaim("CLM-H002", "PAT-002", "PRV-002", "HOSP-B", "Fracture", "X-Ray + Cast",
                LocalDate.of(2024, 2, 5), LocalDate.of(2024, 2, 6), LocalDate.of(2024, 2, 8),
                "300", "600", "500", "250", "100", "1750", "1500");
        seedClaim("CLM-H003", "PAT-003", "PRV-003", "HOSP-C", "Flu", "Consultation",
                LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 2),
                "80", "0", "150", "40", "30", "300", "250");

        seedHistory();
        return credentials;
    }

    /**
     * A deterministic year of legitimate, already-approved historical claims so
     * the per-procedure billing baselines, provider averages and the isolation
     * forest have a realistic "normal" population from the first request.
     */
    private void seedHistory() {
        String[][] people = {
            {"PAT-004", "Maria Garcia", "61", "F", "Austin"}, {"PAT-005", "David Kim", "45", "M", "Dallas"},
            {"PAT-006", "Linda Chen", "33", "F", "Houston"}, {"PAT-007", "Robert Brown", "70", "M", "Austin"},
            {"PAT-008", "Emily Davis", "26", "F", "Dallas"}, {"PAT-009", "James Wilson", "52", "M", "Houston"},
            {"PAT-010", "Sofia Martinez", "40", "F", "Austin"}, {"PAT-011", "Michael Lee", "58", "M", "Dallas"},
            {"PAT-012", "Aisha Khan", "35", "F", "Houston"}, {"PAT-013", "Daniel Moore", "67", "M", "Austin"},
            {"PAT-014", "Grace Taylor", "29", "F", "Dallas"}, {"PAT-015", "Omar Haddad", "48", "M", "Houston"}
        };
        for (String[] p : people) {
            patients.save(new Patient(p[0], p[1], Integer.parseInt(p[2]), p[3], p[4],
                    "INS-" + (1000 + Integer.parseInt(p[0].substring(4)))));
        }
        // procedure, diagnosis, provider, hospital, base amount, stay days
        Object[][] procedures = {
            {"ECG", "Chest pain", "PRV-001", "HOSP-A", 3000, 2},
            {"MRI Scan", "Back pain", "PRV-001", "HOSP-A", 6000, 0},
            {"X-Ray + Cast", "Fracture", "PRV-002", "HOSP-B", 1750, 1},
            {"Knee Surgery", "Ligament tear", "PRV-002", "HOSP-B", 95000, 4},
            {"Consultation", "Flu", "PRV-003", "HOSP-C", 300, 0},
            {"Appendectomy", "Appendicitis", "PRV-003", "HOSP-C", 45000, 3}
        };
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 48; i++) {
            Object[] proc = procedures[i % procedures.length];
            String patientId = people[r.nextInt(people.length)][0];
            LocalDate admission = LocalDate.of(2024, 1 + (i % 12), 1 + r.nextInt(25));
            LocalDate discharge = admission.plusDays((int) proc[5] + (r.nextBoolean() ? 1 : 0) * Math.min(1, (int) proc[5]));
            long total = Math.round((int) proc[4] * (0.88 + r.nextDouble() * 0.24));
            long medicine = Math.round(total * 0.18);
            long room = Math.round(total * ((int) proc[5] == 0 ? 0.0 : 0.30));
            long doctor = Math.round(total * 0.32);
            long lab = Math.round(total * 0.10);
            long other = total - medicine - room - doctor - lab;
            claims.save(new Claim(String.format("CLM-H1%02d", i), patientId, (String) proc[2], (String) proc[3],
                    (String) proc[1], (String) proc[0], admission, discharge, discharge.plusDays(2),
                    BigDecimal.valueOf(medicine), BigDecimal.valueOf(room), BigDecimal.valueOf(doctor),
                    BigDecimal.valueOf(lab), BigDecimal.valueOf(other), BigDecimal.valueOf(total),
                    BigDecimal.valueOf(Math.round(total * 0.85)), "APPROVED",
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i * 3600L)));
        }
    }

    private Credential seedUser(String id, String name, String email, String password, Role role) {
        String salt = hasher.newSalt();
        String hash = hasher.hash(password, salt);
        users.save(new User(id, name, email, hash, salt, role, "ACTIVE", Instant.now()));
        return new Credential(role.name(), email, password);
    }

    private void seedClaim(String claimId, String patientId, String providerId, String hospitalId,
                           String diagnosis, String procedure, LocalDate admission, LocalDate discharge,
                           LocalDate claimDate, String medicine, String room, String doctor, String lab,
                           String other, String total, String insurance) {
        claims.save(new Claim(claimId, patientId, providerId, hospitalId, diagnosis, procedure,
                admission, discharge, claimDate,
                new BigDecimal(medicine), new BigDecimal(room), new BigDecimal(doctor),
                new BigDecimal(lab), new BigDecimal(other), new BigDecimal(total), new BigDecimal(insurance),
                "SUBMITTED", Instant.now()));
    }

    /** Print the seeded login credentials for manual testing. */
    public static void printCredentials(java.util.List<Credential> credentials) {
        System.out.println("---------------------------------------------");
        System.out.println("Seeded login credentials (for manual testing):");
        for (Credential c : credentials) {
            System.out.printf("  %-14s %-28s %s%n", c.role(), c.email(), c.password());
        }
        System.out.println("---------------------------------------------");
    }
}
