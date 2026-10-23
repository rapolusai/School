package com.akshara.hostel;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A complaint or maintenance request about a room. */
@Entity
@Table(schema = "hostel", name = "complaint")
class Complaint extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private UUID roomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintCategory category;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintStatus status;

    @Column(updatable = false)
    private String raisedByName;

    private String resolutionNote;

    private Instant resolvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Complaint() {
    }

    Complaint(UUID hostelId, UUID roomId, ComplaintCategory category, String description, String raisedByName,
            Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.roomId = roomId;
        this.category = category;
        this.description = description;
        this.status = ComplaintStatus.OPEN;
        this.raisedByName = raisedByName;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void move(ComplaintStatus status, String note, Instant now) {
        this.status = status;
        this.resolutionNote = note;
        this.resolvedAt = status == ComplaintStatus.RESOLVED ? now : null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    UUID getRoomId() {
        return roomId;
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

    String getRaisedByName() {
        return raisedByName;
    }

    String getResolutionNote() {
        return resolutionNote;
    }

    Instant getResolvedAt() {
        return resolvedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
