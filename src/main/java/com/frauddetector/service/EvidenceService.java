package com.frauddetector.service;

import com.frauddetector.domain.Evidence;
import com.frauddetector.http.ApiException;
import com.frauddetector.repository.EvidenceRepository;
import com.frauddetector.repository.InvestigationRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Secure evidence upload for investigations (PRD sections 19 and 30
 * "Secure file upload").
 *
 * <ul>
 *   <li>Only an allow-list of content types is accepted (PDF, PNG, JPEG, plain text),
 *       and the file's leading "magic bytes" must match the declared type.</li>
 *   <li>Decoded size is capped ({@link #MAX_BYTES}).</li>
 *   <li>Files are stored under a server-generated id; the client file name is
 *       sanitized and kept only as display metadata, so it cannot cause path
 *       traversal.</li>
 * </ul>
 */
public final class EvidenceService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> ALLOWED = Map.of(
            "application/pdf", ".pdf",
            "image/png", ".png",
            "image/jpeg", ".jpg",
            "text/plain", ".txt");

    private final EvidenceRepository evidence;
    private final InvestigationRepository investigations;
    private final Path storageDir;

    public EvidenceService(EvidenceRepository evidence, InvestigationRepository investigations, Path storageDir) {
        this.evidence = evidence;
        this.investigations = investigations;
        this.storageDir = storageDir;
    }

    public Evidence upload(String investigationId, String fileName, String contentType,
                           String dataBase64, String description, String uploadedBy) {
        if (!investigations.existsById(investigationId)) {
            throw new ApiException(404, "Investigation '" + investigationId + "' not found");
        }
        String type = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!ALLOWED.containsKey(type)) {
            throw new ApiException(400, "Unsupported contentType '" + contentType
                    + "'. Allowed: " + ALLOWED.keySet());
        }
        if (dataBase64 == null || dataBase64.isBlank()) {
            throw new ApiException(400, "dataBase64 is required");
        }
        // Rough pre-check before decoding so a huge payload is rejected cheaply.
        if (dataBase64.length() > (MAX_BYTES * 4 / 3) + 16) {
            throw new ApiException(413, "File exceeds the " + (MAX_BYTES / (1024 * 1024)) + " MB limit");
        }
        byte[] bytes;
        try {
            String payload = dataBase64.contains(",") && dataBase64.startsWith("data:")
                    ? dataBase64.substring(dataBase64.indexOf(',') + 1)
                    : dataBase64;
            bytes = Base64.getDecoder().decode(payload.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "dataBase64 is not valid base64");
        }
        if (bytes.length == 0) {
            throw new ApiException(400, "File is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(413, "File exceeds the " + (MAX_BYTES / (1024 * 1024)) + " MB limit");
        }
        if (!magicMatches(type, bytes)) {
            throw new ApiException(400, "File content does not match declared type " + type);
        }

        String id = "EVD-" + UUID.randomUUID();
        try {
            Files.createDirectories(storageDir);
            Files.write(pathFor(id), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store evidence", e);
        }
        Evidence record = new Evidence(id, investigationId, sanitize(fileName, ALLOWED.get(type)), type,
                bytes.length, description, uploadedBy, Instant.now());
        return evidence.save(record);
    }

    public List<Evidence> list(String investigationId) {
        if (!investigations.existsById(investigationId)) {
            throw new ApiException(404, "Investigation '" + investigationId + "' not found");
        }
        return evidence.findByInvestigationId(investigationId);
    }

    public Evidence get(String evidenceId) {
        return evidence.findById(evidenceId)
                .orElseThrow(() -> new ApiException(404, "Evidence '" + evidenceId + "' not found"));
    }

    public byte[] read(Evidence e) {
        try {
            return Files.readAllBytes(pathFor(e.getEvidenceId()));
        } catch (IOException ex) {
            throw new ApiException(404, "Evidence file for '" + e.getEvidenceId() + "' is missing");
        }
    }

    private Path pathFor(String evidenceId) {
        // evidenceId is always server-generated ("EVD-" + UUID); still guard it.
        if (!evidenceId.matches("EVD-[0-9a-f\\-]{36}")) {
            throw new ApiException(400, "Invalid evidence id");
        }
        return storageDir.resolve(evidenceId + ".bin");
    }

    static String sanitize(String name, String defaultExt) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        base = base.replaceAll("^\\.+", "");
        if (base.isEmpty()) {
            base = "evidence" + defaultExt;
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static boolean magicMatches(String type, byte[] b) {
        switch (type) {
            case "application/pdf":
                return b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
            case "image/png":
                return b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/jpeg":
                return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            case "text/plain":
                for (int i = 0; i < Math.min(b.length, 4096); i++) {
                    if (b[i] == 0) {
                        return false; // binary content masquerading as text
                    }
                }
                return true;
            default:
                return false;
        }
    }
}
