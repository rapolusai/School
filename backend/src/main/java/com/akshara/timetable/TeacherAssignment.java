package com.akshara.timetable;

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

/** Who teaches a subject to a section in an academic year, and how many periods a week it should get. */
@Entity
@Table(schema = "timetable", name = "teacher_assignment")
class TeacherAssignment extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private UUID teacherId;

    @Column(nullable = false)
    private int periodsPerWeek;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TeacherAssignment() {
    }

    TeacherAssignment(UUID academicYearId, UUID sectionId, UUID subjectId, UUID teacherId, int periodsPerWeek) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.sectionId = sectionId;
        this.subjectId = subjectId;
        this.teacherId = teacherId;
        this.periodsPerWeek = periodsPerWeek;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(UUID teacherId, int periodsPerWeek) {
        this.teacherId = teacherId;
        this.periodsPerWeek = periodsPerWeek;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getTeacherId() {
        return teacherId;
    }

    int getPeriodsPerWeek() {
        return periodsPerWeek;
    }
}
