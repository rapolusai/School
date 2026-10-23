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

/** A hostel (boys', girls' or staff) with the warden who runs it. */
@Entity
@Table(schema = "hostel", name = "hostel")
class Hostel extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HostelType type;

    private UUID wardenUserId;

    private String address;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Hostel() {
    }

    Hostel(String name, HostelType type, UUID wardenUserId, String address, Instant now) {
        this.id = Ids.newId();
        this.name = name;
        this.type = type;
        this.wardenUserId = wardenUserId;
        this.address = address;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, HostelType type, UUID wardenUserId, String address, boolean active) {
        this.name = name;
        this.type = type;
        this.wardenUserId = wardenUserId;
        this.address = address;
        this.active = active;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    HostelType getType() {
        return type;
    }

    UUID getWardenUserId() {
        return wardenUserId;
    }

    String getAddress() {
        return address;
    }

    boolean isActive() {
        return active;
    }
}
