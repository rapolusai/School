package com.akshara.files;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One uploaded file: its record, and its bytes unless they are in object storage. Loaded as an entity only to
 * download it; lists use {@link StoredFileInfo} projections so the bytes stay where they are.
 */
@Entity
@Table(schema = "files", name = "stored_file")
class StoredFile extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String ownerModule;

    @Column(nullable = false, updatable = false)
    private String ownerType;

    @Column(nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false, updatable = false)
    private String originalName;

    @Column(nullable = false, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    /** Null when the bytes are in object storage ({@link #storage} S3). */
    private byte[] bytes;

    /** Where the bytes are: {@value #DATABASE} (in {@link #bytes}) or {@value #S3}. */
    @Column(nullable = false)
    private String storage;

    @Column(updatable = false)
    private UUID uploadedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StoredFile() {
    }

    static final String DATABASE = "DATABASE";
    static final String S3 = "S3";

    /** {@code inDatabase}: keep the bytes in this row; otherwise the caller writes them to object storage. */
    StoredFile(FileOwner owner, IncomingFile file, UUID uploadedBy, boolean inDatabase) {
        this.id = Ids.newId();
        this.ownerModule = owner.module();
        this.ownerType = owner.type();
        this.ownerId = owner.id();
        this.originalName = file.name();
        this.contentType = file.type().mediaType();
        this.sizeBytes = file.size();
        this.sha256 = file.sha256();
        this.bytes = inDatabase ? file.bytes() : null;
        this.storage = inDatabase ? DATABASE : S3;
        this.uploadedBy = uploadedBy;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    byte[] getBytes() {
        return bytes;
    }

    boolean inObjectStorage() {
        return S3.equals(storage);
    }

    UUID getTenantId() {
        return tenantId;
    }

    /** After the bytes were copied to object storage: the row keeps only the record. */
    void movedToObjectStorage() {
        this.bytes = null;
        this.storage = S3;
    }

    StoredFileInfo info() {
        return new StoredFileInfo(id, ownerModule, ownerType, ownerId, originalName, contentType, sizeBytes, sha256,
                uploadedBy, createdAt);
    }
}
