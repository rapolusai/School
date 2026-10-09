package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** Homework set for one subject in one or more sections ({@link HomeworkSection}). */
@Entity
@Table(schema = "homework", name = "homework")
class Homework extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String instructions;

    @Column(nullable = false)
    private LocalDate assignedOn;

    @Column(nullable = false)
    private LocalDate dueOn;

    @Column(nullable = false)
    private boolean onlineSubmission;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    private Instant dueReminderAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Homework() {
    }

    Homework(UUID academicYearId, UUID subjectId, String title, String instructions, LocalDate assignedOn,
            LocalDate dueOn, boolean onlineSubmission, Actor by, Instant at) {
        this.id = Ids.newId();
        this.academicYearId = academicYearId;
        this.subjectId = subjectId;
        this.title = title;
        this.instructions = instructions;
        this.assignedOn = assignedOn;
        this.dueOn = dueOn;
        this.onlineSubmission = onlineSubmission;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(UUID subjectId, String title, String instructions, LocalDate dueOn, boolean onlineSubmission) {
        if (!dueOn.equals(this.dueOn)) {
            // A new due date gets its own evening-before reminder.
            this.dueReminderAt = null;
        }
        this.subjectId = subjectId;
        this.title = title;
        this.instructions = instructions;
        this.dueOn = dueOn;
        this.onlineSubmission = onlineSubmission;
    }

    void dueReminderSent(Instant at) {
        this.dueReminderAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    String getTitle() {
        return title;
    }

    String getInstructions() {
        return instructions;
    }

    LocalDate getAssignedOn() {
        return assignedOn;
    }

    LocalDate getDueOn() {
        return dueOn;
    }

    boolean isOnlineSubmission() {
        return onlineSubmission;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getDueReminderAt() {
        return dueReminderAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
