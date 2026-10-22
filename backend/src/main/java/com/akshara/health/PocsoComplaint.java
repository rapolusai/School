package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One entry of the POCSO complaints register. Read only by the committee, every read audited; the complainant's
 * identity lives only in {@code complainantNote}, free text shown on the detail alone. Never deleted.
 */
@Entity
@Table(schema = "health", name = "pocso_complaint")
class PocsoComplaint extends AssignedIdEntity {

    record Details(LocalDate complaintDate, ComplaintCategory category, String description, ComplaintStatus status,
            String actions, String complainantNote) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private LocalDate complaintDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintCategory category;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintStatus status;

    private String actions;

    private String complainantNote;

    @Column(updatable = false)
    private String recordedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PocsoComplaint() {
    }

    PocsoComplaint(Details d, String recordedByName, Instant now) {
        this.id = Ids.newId();
        this.recordedByName = recordedByName;
        this.createdAt = now;
        this.updatedAt = now;
        apply(d);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.complaintDate = d.complaintDate();
        this.category = d.category();
        this.description = d.description();
        this.status = d.status();
        this.actions = d.actions();
        this.complainantNote = d.complainantNote();
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getComplaintDate() {
        return complaintDate;
    }

    ComplaintCategory getCategory() {
        return category;
    }

    String getDescription() {
        return description;
    }

    ComplaintStatus getStatus() {
        return status;
    }

    String getActions() {
        return actions;
    }

    String getComplainantNote() {
        return complainantNote;
    }

    String getRecordedByName() {
        return recordedByName;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
