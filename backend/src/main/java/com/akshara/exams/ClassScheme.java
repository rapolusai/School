package com.akshara.exams;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** The grading scheme a class uses in an academic year. Without one, the default for the school's board applies. */
@Entity
@Table(schema = "exams", name = "class_scheme")
class ClassScheme extends AssignedIdEntity {

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
    private UUID schemeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ClassScheme() {
    }

    ClassScheme(UUID academicYearId, UUID classId, UUID schemeId) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.classId = classId;
        this.schemeId = schemeId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void change(UUID schemeId) {
        this.schemeId = schemeId;
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

    UUID getSchemeId() {
        return schemeId;
    }
}
