package com.akshara.students;

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

/** A student's place in a section for one academic year, with an optional roll number. */
@Entity
@Table(schema = "students", name = "enrollment")
class Enrollment extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private UUID sectionId;

    private Integer rollNo;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Enrollment() {
    }

    Enrollment(UUID studentId, UUID academicYearId, UUID sectionId, Integer rollNo) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.academicYearId = academicYearId;
        this.sectionId = sectionId;
        this.rollNo = rollNo;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void moveTo(UUID sectionId, Integer rollNo) {
        this.sectionId = sectionId;
        this.rollNo = rollNo;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    Integer getRollNo() {
        return rollNo;
    }
}
