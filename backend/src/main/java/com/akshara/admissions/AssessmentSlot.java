package com.akshara.admissions;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.admissions.AdmissionTypes.AssessmentKind;
import com.akshara.admissions.AdmissionTypes.AssessmentMode;
import com.akshara.admissions.AdmissionTypes.SlotStatus;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** An entrance test or interview for one application: when, where (or the online link) and with whom. */
@Entity
@Table(schema = "admissions", name = "assessment_slot")
class AssessmentSlot extends AssignedIdEntity {

    /** The schedulable part of a slot, validated by the caller. */
    record Plan(AssessmentKind kind, Instant scheduledAt, AssessmentMode mode, String location, String meetingLink,
            UUID interviewerId) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentKind kind;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentMode mode;

    private String location;

    private String meetingLink;

    private UUID interviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlotStatus status;

    private String outcomeNotes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AssessmentSlot() {
    }

    AssessmentSlot(UUID applicationId, Plan plan) {
        this.id = Ids.newId();
        this.applicationId = applicationId;
        this.status = SlotStatus.SCHEDULED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        reschedule(plan);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void reschedule(Plan plan) {
        this.kind = plan.kind();
        this.scheduledAt = plan.scheduledAt();
        this.mode = plan.mode();
        this.location = plan.mode() == AssessmentMode.IN_PERSON ? plan.location() : null;
        this.meetingLink = plan.mode() == AssessmentMode.ONLINE ? plan.meetingLink() : null;
        this.interviewerId = plan.interviewerId();
    }

    void recordOutcome(String notes) {
        this.status = SlotStatus.DONE;
        this.outcomeNotes = notes;
    }

    void cancel() {
        this.status = SlotStatus.CANCELLED;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    AssessmentKind getKind() {
        return kind;
    }

    Instant getScheduledAt() {
        return scheduledAt;
    }

    AssessmentMode getMode() {
        return mode;
    }

    String getLocation() {
        return location;
    }

    String getMeetingLink() {
        return meetingLink;
    }

    UUID getInterviewerId() {
        return interviewerId;
    }

    SlotStatus getStatus() {
        return status;
    }

    String getOutcomeNotes() {
        return outcomeNotes;
    }
}
