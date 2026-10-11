package com.akshara.lessons;

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

/** The syllabus of one subject for one class in an academic year; its units and topics are separate rows. */
@Entity
@Table(schema = "lessons", name = "syllabus")
class Syllabus extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID classId;

    @Column(nullable = false, updatable = false)
    private UUID subjectId;

    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Syllabus() {
    }

    Syllabus(UUID academicYearId, UUID classId, UUID subjectId, String updatedByName) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.classId = classId;
        this.subjectId = subjectId;
        this.updatedByName = updatedByName;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Marks the syllabus as changed (its units or topics), so its version and updated time move. */
    void changed(String byName) {
        this.updatedByName = byName;
        this.updatedAt = Instant.now();
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

    UUID getSubjectId() {
        return subjectId;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getVersion() {
        return version;
    }
}
