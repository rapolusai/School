package com.akshara.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A teacher who is away on a day, so their periods need substitutes. */
@Entity
@Table(schema = "timetable", name = "teacher_absence")
class TeacherAbsence extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private LocalDate absenceDate;

    @Column(nullable = false, updatable = false)
    private UUID teacherId;

    private String reason;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TeacherAbsence() {
    }

    TeacherAbsence(LocalDate date, UUID teacherId, String reason, Actor by) {
        this.id = Ids.newId();
        this.absenceDate = date;
        this.teacherId = teacherId;
        this.reason = reason;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getAbsenceDate() {
        return absenceDate;
    }

    UUID getTeacherId() {
        return teacherId;
    }

    String getReason() {
        return reason;
    }
}
