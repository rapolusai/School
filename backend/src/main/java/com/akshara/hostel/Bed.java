package com.akshara.hostel;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One bed in a room, labelled as the room shows it ("A", "B", "1"...). */
@Entity
@Table(schema = "hostel", name = "bed")
class Bed extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false, updatable = false)
    private UUID roomId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Bed() {
    }

    Bed(UUID hostelId, UUID roomId, String label, Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.roomId = roomId;
        this.label = label;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
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

    String getLabel() {
        return label;
    }
}
