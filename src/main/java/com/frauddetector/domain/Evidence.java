package com.frauddetector.domain;

import java.time.Instant;

/**
 * A supporting document attached to an investigation (PRD section 19). The file
 * bytes live on disk under a server-generated name; this record holds only the
 * metadata, so a user-supplied file name can never influence the storage path.
 */
public final class Evidence {

    private final String evidenceId;
    private final String investigationId;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final String description;
    private final String uploadedBy;
    private final Instant uploadedAt;

    public Evidence(String evidenceId, String investigationId, String fileName, String contentType,
                    long sizeBytes, String description, String uploadedBy, Instant uploadedAt) {
        this.evidenceId = evidenceId;
        this.investigationId = investigationId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.description = description;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getDescription() {
        return description;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
