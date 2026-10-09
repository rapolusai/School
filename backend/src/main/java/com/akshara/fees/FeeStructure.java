package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * What one class pays in one academic year: its instalments and each instalment's share of each head. A DRAFT can be
 * edited freely; publishing creates the students' dues, and later edits regenerate only what is still unpaid.
 */
@Entity
@Table(schema = "fees", name = "fee_structure")
class FeeStructure extends AssignedIdEntity {

    static final String DRAFT = "DRAFT";
    static final String PUBLISHED = "PUBLISHED";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID classId;

    @Column(nullable = false)
    private String status;

    private Instant publishedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FeeStructure() {
    }

    FeeStructure(UUID academicYearId, UUID classId) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.classId = classId;
        this.status = DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void publish() {
        status = PUBLISHED;
        publishedAt = Instant.now();
    }

    /** Marks an edit, so the version changes even when only child rows did. */
    void edited() {
        updatedAt = Instant.now();
    }

    boolean isPublished() {
        return PUBLISHED.equals(status);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getClassId() {
        return classId;
    }

    String getStatus() {
        return status;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }
}
