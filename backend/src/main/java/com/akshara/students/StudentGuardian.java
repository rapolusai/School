package com.akshara.students;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** Links a student to a guardian. Each student has exactly one primary guardian, the first person the school calls. */
@Entity
@Table(schema = "students", name = "student_guardian")
class StudentGuardian extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID guardianId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StudentGuardian() {
    }

    StudentGuardian(UUID studentId, UUID guardianId, boolean primary) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.guardianId = guardianId;
        this.primary = primary;
        this.createdAt = Instant.now();
    }

    void setPrimary(boolean primary) {
        this.primary = primary;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getGuardianId() {
        return guardianId;
    }

    boolean isPrimary() {
        return primary;
    }
}
