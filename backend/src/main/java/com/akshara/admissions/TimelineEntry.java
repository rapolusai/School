package com.akshara.admissions;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.admissions.AdmissionTypes.TimelineKind;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One thing that happened to an application, and who did it. Entries are never changed. */
@Entity
@Immutable
@Table(schema = "admissions", name = "timeline_entry")
class TimelineEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimelineKind kind;

    private UUID actorId;

    private String actorName;

    @Enumerated(EnumType.STRING)
    private ApplicationStage fromStage;

    @Enumerated(EnumType.STRING)
    private ApplicationStage toStage;

    private String note;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String details;

    protected TimelineEntry() {
    }

    TimelineEntry(UUID applicationId, TimelineKind kind, UUID actorId, String actorName, ApplicationStage fromStage,
            ApplicationStage toStage, String note, String details) {
        this.id = Ids.newId();
        this.applicationId = applicationId;
        this.at = Instant.now();
        this.kind = kind;
        this.actorId = actorId;
        this.actorName = actorName;
        this.fromStage = fromStage;
        this.toStage = toStage;
        this.note = note;
        this.details = details;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    Instant getAt() {
        return at;
    }

    TimelineKind getKind() {
        return kind;
    }

    String getActorName() {
        return actorName;
    }

    ApplicationStage getFromStage() {
        return fromStage;
    }

    ApplicationStage getToStage() {
        return toStage;
    }

    String getNote() {
        return note;
    }

    String getDetails() {
        return details;
    }
}
