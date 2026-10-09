package com.akshara.privacy;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * The ZIP file made for an access request. The file is deleted when it expires (7 days), when a newer export replaces
 * it, or when the child's data is erased; the row stays as a record of what was handed over and when.
 */
@Entity
@Table(schema = "privacy", name = "data_export")
class DataExport extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID requestId;

    @Column(updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private String fileName;

    /** The ZIP file; null once it is deleted. */
    private byte[] content;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExportStatus status;

    @Column(updatable = false)
    private UUID createdById;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant deletedAt;

    @Column(nullable = false)
    private int downloadCount;

    private Instant lastDownloadedAt;

    @Version
    private long version;

    protected DataExport() {
    }

    DataExport(UUID requestId, UUID studentId, String fileName, byte[] content, String sha256, UUID createdById,
            String createdByName, Instant createdAt, Instant expiresAt) {
        this.id = Ids.newId();
        this.requestId = requestId;
        this.studentId = studentId;
        this.fileName = fileName;
        this.content = content;
        this.sizeBytes = content.length;
        this.sha256 = sha256;
        this.status = ExportStatus.READY;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    boolean downloadable(Instant now) {
        return status == ExportStatus.READY && content != null && expiresAt.isAfter(now);
    }

    /** Deletes the file and keeps the record. */
    void discard(ExportStatus why, Instant at) {
        this.status = why;
        this.content = null;
        this.deletedAt = at;
    }

    void downloaded(Instant at) {
        downloadCount++;
        lastDownloadedAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRequestId() {
        return requestId;
    }

    String getFileName() {
        return fileName;
    }

    byte[] getContent() {
        return content;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    ExportStatus getStatus() {
        return status;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    int getDownloadCount() {
        return downloadCount;
    }

    Instant getLastDownloadedAt() {
        return lastDownloadedAt;
    }
}
