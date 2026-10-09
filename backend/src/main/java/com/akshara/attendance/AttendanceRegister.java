package com.akshara.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A section's attendance for one school day: who marked it first and when, and who changed it last. */
@Entity
@Table(schema = "attendance", name = "register")
class AttendanceRegister extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private LocalDate attendanceDate;

    @Column(updatable = false)
    private UUID markedById;

    @Column(updatable = false)
    private String markedByName;

    @Column(nullable = false, updatable = false)
    private Instant markedAt;

    private UUID updatedById;

    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** When the register was last saved (equal to markedAt until someone changes it). */
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AttendanceRegister() {
    }

    AttendanceRegister(UUID sectionId, UUID academicYearId, LocalDate date, Actor markedBy, Instant at) {
        this.id = Ids.newId();
        this.sectionId = sectionId;
        this.academicYearId = academicYearId;
        this.attendanceDate = date;
        this.markedById = markedBy.id();
        this.markedByName = markedBy.name();
        this.markedAt = at;
        this.createdAt = at;
        this.updatedAt = at;
    }

    void edited(Actor by, Instant at) {
        this.updatedById = by.id();
        this.updatedByName = by.name();
        this.updatedAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getSectionId() {
        return sectionId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    LocalDate getAttendanceDate() {
        return attendanceDate;
    }

    String getMarkedByName() {
        return markedByName;
    }

    Instant getMarkedAt() {
        return markedAt;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    /** Null until someone changes the register after it was first marked. */
    Instant getEditedAt() {
        return updatedByName == null && updatedById == null ? null : updatedAt;
    }
}
