package com.akshara.hostel;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A block (wing or building) of a hostel, with its number of floors. */
@Entity
@Table(schema = "hostel", name = "block")
class Block extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int floors;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Block() {
    }

    Block(UUID hostelId, String name, int floors, Instant now) {
        this.id = Ids.newId();
        this.hostelId = hostelId;
        this.name = name;
        this.floors = floors;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, int floors) {
        this.name = name;
        this.floors = floors;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getHostelId() {
        return hostelId;
    }

    String getName() {
        return name;
    }

    int getFloors() {
        return floors;
    }
}
