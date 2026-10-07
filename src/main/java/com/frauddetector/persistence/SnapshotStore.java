package com.frauddetector.persistence;

import com.frauddetector.domain.Claim;
import com.frauddetector.domain.Evidence;
import com.frauddetector.domain.FraudAnalysis;
import com.frauddetector.domain.Investigation;
import com.frauddetector.domain.Notification;
import com.frauddetector.domain.Patient;
import com.frauddetector.domain.Provider;
import com.frauddetector.domain.RiskFactor;
import com.frauddetector.domain.User;
import com.frauddetector.http.Json;
import com.frauddetector.repository.AuditLogRepository;
import com.frauddetector.repository.ClaimRepository;
import com.frauddetector.repository.EvidenceRepository;
import com.frauddetector.repository.FraudAnalysisRepository;
import com.frauddetector.repository.InMemoryRepository;
import com.frauddetector.repository.InvestigationRepository;
import com.frauddetector.repository.NotificationRepository;
import com.frauddetector.repository.PatientRepository;
import com.frauddetector.repository.ProviderRepository;
import com.frauddetector.repository.UserRepository;
import com.frauddetector.security.AuditLog;
import com.frauddetector.security.Role;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Durable storage for the in-memory repositories (PRD section 32 "No loss of
 * claim information", "Database backups").
 *
 * <p>No JDBC driver can be bundled in this zero-dependency build, so the whole
 * data set is written as one JSON snapshot ({@code data/snapshot.json}). Writes
 * go to a temp file that is atomically moved into place, and the previous
 * snapshot is kept as {@code snapshot.json.bak}. A background task saves within
 * a couple of seconds of any change and a JVM shutdown hook saves on exit.
 * Repositories stay behind the same interfaces, so swapping this for a real
 * database later does not affect the services.
 */
public final class SnapshotStore {

    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private final UserRepository users;
    private final PatientRepository patients;
    private final ProviderRepository providers;
    private final ClaimRepository claims;
    private final FraudAnalysisRepository analyses;
    private final InvestigationRepository investigations;
    private final NotificationRepository notifications;
    private final AuditLogRepository auditLogs;
    private final EvidenceRepository evidence;

    private volatile long savedAt = -1;
    private ScheduledExecutorService scheduler;

    public SnapshotStore(Path file, UserRepository users, PatientRepository patients,
                         ProviderRepository providers, ClaimRepository claims,
                         FraudAnalysisRepository analyses, InvestigationRepository investigations,
                         NotificationRepository notifications, AuditLogRepository auditLogs,
                         EvidenceRepository evidence) {
        this.file = file;
        this.users = users;
        this.patients = patients;
        this.providers = providers;
        this.claims = claims;
        this.analyses = analyses;
        this.investigations = investigations;
        this.notifications = notifications;
        this.auditLogs = auditLogs;
        this.evidence = evidence;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    /** Restore all repositories from the snapshot. Returns false if there is none. */
    @SuppressWarnings("unchecked")
    public synchronized boolean load() throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        restore(users, root.get("users"), SnapshotStore::user);
        restore(patients, root.get("patients"), SnapshotStore::patient);
        restore(providers, root.get("providers"), SnapshotStore::provider);
        restore(claims, root.get("claims"), SnapshotStore::claim);
        restore(analyses, root.get("analyses"), SnapshotStore::analysis);
        restore(investigations, root.get("investigations"), SnapshotStore::investigation);
        restore(notifications, root.get("notifications"), SnapshotStore::notification);
        restore(auditLogs, root.get("auditLogs"), SnapshotStore::auditLog);
        restore(evidence, root.get("evidence"), SnapshotStore::evidence);
        savedAt = InMemoryRepository.modificationCount();
        return true;
    }

    /** Write the snapshot atomically (temp file + move, previous kept as .bak). */
    public synchronized void save() throws IOException {
        long version = InMemoryRepository.modificationCount();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("savedAt", Instant.now().toString());
        root.put("users", map(users.findAll(), SnapshotStore::user));
        root.put("patients", map(patients.findAll(), SnapshotStore::patient));
        root.put("providers", map(providers.findAll(), SnapshotStore::provider));
        root.put("claims", map(claims.findAll(), SnapshotStore::claim));
        root.put("analyses", map(analyses.findAll(), SnapshotStore::analysis));
        root.put("investigations", map(investigations.findAll(), SnapshotStore::investigation));
        root.put("notifications", map(notifications.findAll(), SnapshotStore::notification));
        root.put("auditLogs", map(auditLogs.findAll(), SnapshotStore::auditLog));
        root.put("evidence", map(evidence.findAll(), SnapshotStore::evidence));

        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        if (Files.exists(file)) {
            Files.copy(file, dir.resolve(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        savedAt = version;
    }

    /** Save every {@code intervalSeconds} when something changed, and on JVM shutdown. */
    public void startAutoSave(int intervalSeconds) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-autosave");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::saveIfDirty, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveIfDirty, "snapshot-shutdown"));
    }

    public void saveIfDirty() {
        if (InMemoryRepository.modificationCount() == savedAt) {
            return;
        }
        try {
            save();
        } catch (IOException | RuntimeException e) {
            System.err.println("WARN: snapshot save failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface FromMap<T> {
        T apply(Map<String, Object> m);
    }

    private interface ToMap<T> {
        Map<String, Object> apply(T t);
    }

    @SuppressWarnings("unchecked")
    private static <T> void restore(InMemoryRepository<String, T> repo, Object raw, FromMap<T> fn) {
        repo.clear();
        if (!(raw instanceof List<?>)) {
            return;
        }
        for (Object o : (List<Object>) raw) {
            if (o instanceof Map<?, ?>) {
                repo.save(fn.apply((Map<String, Object>) o));
            }
        }
    }

    private static <T> List<Map<String, Object>> map(List<T> items, ToMap<T> fn) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (T t : items) {
            out.add(fn.apply(t));
        }
        return out;
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static BigDecimal dec(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static String dec(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static double dbl(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0.0 : Double.parseDouble(v.toString());
    }

    private static LocalDate date(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : LocalDate.parse(v.toString());
    }

    private static String date(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static Instant inst(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : Instant.parse(v.toString());
    }

    private static String inst(Instant i) {
        return i == null ? null : i.toString();
    }

    // ------------------------------------------------------------------ entity codecs

    private static Map<String, Object> user(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("passwordHash", u.getPasswordHash());
        m.put("salt", u.getSalt());
        m.put("role", u.getRole().name());
        m.put("status", u.getStatus());
        m.put("createdAt", inst(u.getCreatedAt()));
        return m;
    }

    private static User user(Map<String, Object> m) {
        return new User(s(m, "id"), s(m, "name"), s(m, "email"), s(m, "passwordHash"), s(m, "salt"),
                Role.valueOf(s(m, "role")), s(m, "status"), inst(m, "createdAt"));
    }

    private static Map<String, Object> patient(Patient p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", p.getPatientId());
        m.put("name", p.getName());
        m.put("age", p.getAge());
        m.put("gender", p.getGender());
        m.put("location", p.getLocation());
        m.put("insuranceId", p.getInsuranceId());
        return m;
    }

    private static Patient patient(Map<String, Object> m) {
        return new Patient(s(m, "patientId"), s(m, "name"), (int) dbl(m, "age"), s(m, "gender"),
                s(m, "location"), s(m, "insuranceId"));
    }

    private static Map<String, Object> provider(Provider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerId", p.getProviderId());
        m.put("providerName", p.getProviderName());
        m.put("hospital", p.getHospital());
        m.put("location", p.getLocation());
        m.put("specialization", p.getSpecialization());
        m.put("riskScore", dec(p.getRiskScore()));
        return m;
    }

    private static Provider provider(Map<String, Object> m) {
        return new Provider(s(m, "providerId"), s(m, "providerName"), s(m, "hospital"), s(m, "location"),
                s(m, "specialization"), dec(m, "riskScore"));
    }

    private static Map<String, Object> claim(Claim c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claimId", c.getClaimId());
        m.put("patientId", c.getPatientId());
        m.put("providerId", c.getProviderId());
        m.put("hospitalId", c.getHospitalId());
        m.put("diagnosis", c.getDiagnosis());
        m.put("procedure", c.getProcedure());
        m.put("admissionDate", date(c.getAdmissionDate()));
        m.put("dischargeDate", date(c.getDischargeDate()));
        m.put("claimDate", date(c.getClaimDate()));
        m.put("medicineCost", dec(c.getMedicineCost()));
        m.put("roomCharges", dec(c.getRoomCharges()));
        m.put("doctorCharges", dec(c.getDoctorCharges()));
        m.put("labCharges", dec(c.getLabCharges()));
        m.put("otherCharges", dec(c.getOtherCharges()));
        m.put("totalBilledAmount", dec(c.getTotalBilledAmount()));
        m.put("insuranceAmount", dec(c.getInsuranceAmount()));
        m.put("claimStatus", c.getClaimStatus());
        m.put("createdAt", inst(c.getCreatedAt()));
        return m;
    }

    private static Claim claim(Map<String, Object> m) {
        return new Claim(s(m, "claimId"), s(m, "patientId"), s(m, "providerId"), s(m, "hospitalId"),
                s(m, "diagnosis"), s(m, "procedure"), date(m, "admissionDate"), date(m, "dischargeDate"),
                date(m, "claimDate"), dec(m, "medicineCost"), dec(m, "roomCharges"), dec(m, "doctorCharges"),
                dec(m, "labCharges"), dec(m, "otherCharges"), dec(m, "totalBilledAmount"),
                dec(m, "insuranceAmount"), s(m, "claimStatus"), inst(m, "createdAt"));
    }

    private static Map<String, Object> analysis(FraudAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.getAnalysisId());
        m.put("claimId", a.getClaimId());
        m.put("fraudProbability", a.getFraudProbability());
        m.put("billingAnomalyScore", a.getBillingAnomalyScore());
        m.put("duplicateScore", a.getDuplicateScore());
        m.put("providerRiskScore", a.getProviderRiskScore());
        m.put("patientRiskScore", a.getPatientRiskScore());
        m.put("finalRiskScore", a.getFinalRiskScore());
        m.put("riskLevel", a.getRiskLevel());
        m.put("modelVersion", a.getModelVersion());
        List<Map<String, Object>> factors = new ArrayList<>();
        for (RiskFactor f : a.getRiskFactors()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("description", f.getDescription());
            fm.put("contribution", f.getContribution());
            factors.add(fm);
        }
        m.put("riskFactors", factors);
        m.put("createdAt", inst(a.getCreatedAt()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static FraudAnalysis analysis(Map<String, Object> m) {
        List<RiskFactor> factors = new ArrayList<>();
        Object raw = m.get("riskFactors");
        if (raw instanceof List<?>) {
            for (Object o : (List<Object>) raw) {
                Map<String, Object> fm = (Map<String, Object>) o;
                factors.add(new RiskFactor(s(fm, "description"), dbl(fm, "contribution")));
            }
        }
        return new FraudAnalysis(s(m, "analysisId"), s(m, "claimId"), dbl(m, "fraudProbability"),
                dbl(m, "billingAnomalyScore"), dbl(m, "duplicateScore"), dbl(m, "providerRiskScore"),
                dbl(m, "patientRiskScore"), dbl(m, "finalRiskScore"), s(m, "riskLevel"),
                s(m, "modelVersion"), factors, inst(m, "createdAt"));
    }

    private static Map<String, Object> investigation(Investigation i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("investigationId", i.getInvestigationId());
        m.put("claimId", i.getClaimId());
        m.put("investigatorId", i.getInvestigatorId());
        m.put("status", i.getStatus());
        m.put("decision", i.getDecision() == null ? null : i.getDecision().name());
        m.put("notes", i.getNotes());
        m.put("createdAt", inst(i.getCreatedAt()));
        m.put("updatedAt", inst(i.getUpdatedAt()));
        return m;
    }

    private static Investigation investigation(Map<String, Object> m) {
        String d = s(m, "decision");
        return new Investigation(s(m, "investigationId"), s(m, "claimId"), s(m, "investigatorId"),
                s(m, "status"), d == null ? null : Investigation.Decision.valueOf(d), s(m, "notes"),
                inst(m, "createdAt"), inst(m, "updatedAt"));
    }

    private static Map<String, Object> notification(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("targetRole", n.getTargetRole().name());
        m.put("type", n.getType());
        m.put("message", n.getMessage());
        m.put("claimId", n.getClaimId());
        m.put("severity", n.getSeverity());
        m.put("read", n.isRead());
        m.put("createdAt", inst(n.getCreatedAt()));
        return m;
    }

    private static Notification notification(Map<String, Object> m) {
        return new Notification(s(m, "id"), Role.valueOf(s(m, "targetRole")), s(m, "type"), s(m, "message"),
                s(m, "claimId"), s(m, "severity"), Boolean.TRUE.equals(m.get("read")), inst(m, "createdAt"));
    }

    private static Map<String, Object> auditLog(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("userId", a.getUserId());
        m.put("action", a.getAction());
        m.put("targetId", a.getTargetId());
        m.put("timestamp", inst(a.getTimestamp()));
        m.put("ip", a.getIp());
        return m;
    }

    private static AuditLog auditLog(Map<String, Object> m) {
        return new AuditLog(s(m, "id"), s(m, "userId"), s(m, "action"), s(m, "targetId"),
                inst(m, "timestamp"), s(m, "ip"));
    }

    private static Map<String, Object> evidence(Evidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidenceId", e.getEvidenceId());
        m.put("investigationId", e.getInvestigationId());
        m.put("fileName", e.getFileName());
        m.put("contentType", e.getContentType());
        m.put("sizeBytes", e.getSizeBytes());
        m.put("description", e.getDescription());
        m.put("uploadedBy", e.getUploadedBy());
        m.put("uploadedAt", inst(e.getUploadedAt()));
        return m;
    }

    private static Evidence evidence(Map<String, Object> m) {
        return new Evidence(s(m, "evidenceId"), s(m, "investigationId"), s(m, "fileName"),
                s(m, "contentType"), (long) dbl(m, "sizeBytes"), s(m, "description"), s(m, "uploadedBy"),
                inst(m, "uploadedAt"));
    }
}
