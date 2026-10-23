package com.akshara.inventory;

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

/** A physical verification round of the assets of a location (or all). */
@Entity
@Table(schema = "inventory", name = "verification_round")
class VerificationRound extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockTakeStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDate startedOn;

    private Instant closedAt;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected VerificationRound() {
    }

    VerificationRound(String name, UUID locationId, StockTakeStatus status, LocalDate startedOn, Instant closedAt, String createdByName) {
        this.id = Ids.newId();
        this.name = name;
        this.locationId = locationId;
        this.status = status;
        this.startedOn = startedOn;
        this.closedAt = closedAt;
        this.createdByName = createdByName;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getName() {
        return name;
    }

    UUID getLocationId() {
        return locationId;
    }

    StockTakeStatus getStatus() {
        return status;
    }

    void setStatus(StockTakeStatus status) {
        this.status = status;
    }

    LocalDate getStartedOn() {
        return startedOn;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    String getCreatedByName() {
        return createdByName;
    }
}
