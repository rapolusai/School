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

/** Who covers one period of an absent teacher on a date. The period's details are kept as they were that day. */
@Entity
@Table(schema = "timetable", name = "substitution")
class Substitution extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID absenceId;

    @Column(nullable = false, updatable = false)
    private LocalDate subDate;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private int periodNo;

    @Column(nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false, updatable = false)
    private UUID absentTeacherId;

    @Column(nullable = false)
    private UUID substituteTeacherId;

    private UUID createdById;

    private String createdByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected Substitution() {
    }

    Substitution(UUID absenceId, LocalDate date, UUID sectionId, int periodNo, UUID subjectId, UUID absentTeacherId,
            UUID substituteTeacherId, Actor by) {
        this.id = Ids.newId();
        this.absenceId = absenceId;
        this.subDate = date;
        this.sectionId = sectionId;
        this.periodNo = periodNo;
        this.subjectId = subjectId;
        this.absentTeacherId = absentTeacherId;
        this.substituteTeacherId = substituteTeacherId;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = Instant.now();
    }

    void reassign(UUID substituteTeacherId, Actor by) {
        this.substituteTeacherId = substituteTeacherId;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAbsenceId() {
        return absenceId;
    }

    LocalDate getSubDate() {
        return subDate;
    }

    UUID getSectionId() {
        return sectionId;
    }

    int getPeriodNo() {
        return periodNo;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    UUID getAbsentTeacherId() {
        return absentTeacherId;
    }

    UUID getSubstituteTeacherId() {
        return substituteTeacherId;
    }
}
