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
 * One uploaded file and its bytes. Loaded as an entity only to download it; lists use {@link StoredFileInfo}
 * projections so the bytes stay in the database.
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

    @Column(nullable = false, updatable = false)
    private byte[] bytes;

    @Column(updatable = false)
    private UUID uploadedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StoredFile() {
    }

    StoredFile(FileOwner owner, IncomingFile file, UUID uploadedBy) {
        this.id = Ids.newId();
        this.ownerModule = owner.module();
        this.ownerType = owner.type();
        this.ownerId = owner.id();
        this.originalName = file.name();
        this.contentType = file.type().mediaType();
        this.sizeBytes = file.size();
        this.sha256 = file.sha256();
        this.bytes = file.bytes();
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

    StoredFileInfo info() {
        return new StoredFileInfo(id, ownerModule, ownerType, ownerId, originalName, contentType, sizeBytes, sha256,
                uploadedBy, createdAt);
    }
}
