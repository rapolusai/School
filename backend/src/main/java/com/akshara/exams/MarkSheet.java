package com.akshara.exams;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * The marks of one paper for one section: a draft while teachers enter them, locked once submitted. Every save moves
 * the version, which the web app sends back so that two people editing the same sheet do not overwrite each other.
 */
@Entity
@Table(schema = "exams", name = "mark_sheet")
class MarkSheet extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID paperId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SheetStatus status;

    private String updatedByName;

    private Instant submittedAt;

    private String submittedByName;

    private Instant unlockedAt;

    private String unlockedByName;

    private String unlockReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected MarkSheet() {
    }

    MarkSheet(UUID paperId, UUID sectionId, Actor by, Instant at) {
        this.id = Ids.newId();
        this.paperId = paperId;
        this.sectionId = sectionId;
        this.status = SheetStatus.DRAFT;
        this.updatedByName = by.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    void saved(Actor by, Instant at) {
        this.updatedByName = by.name();
        this.updatedAt = at;
    }

    void lock(Actor by, Instant at) {
        this.status = SheetStatus.LOCKED;
        this.submittedAt = at;
        this.submittedByName = by.name();
        this.updatedAt = at;
    }

    void unlock(Actor by, String reason, Instant at) {
        this.status = SheetStatus.DRAFT;
        this.unlockedAt = at;
        this.unlockedByName = by.name();
        this.unlockReason = reason;
        this.updatedAt = at;
    }

    boolean locked() {
        return status == SheetStatus.LOCKED;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getPaperId() {
        return paperId;
    }

    UUID getSectionId() {
        return sectionId;
    }

    SheetStatus getStatus() {
        return status;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    String getSubmittedByName() {
        return submittedByName;
    }

    Instant getUnlockedAt() {
        return unlockedAt;
    }

    String getUnlockedByName() {
        return unlockedByName;
    }

    String getUnlockReason() {
        return unlockReason;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getVersion() {
        return version;
    }
}
