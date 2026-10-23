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

/** A stock take: expected quantities frozen at the start, counts entered, variance posted on close. */
@Entity
@Table(schema = "inventory", name = "stock_take")
class StockTake extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockTakeStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDate startedOn;

    private Instant closedAt;

    private String notes;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StockTake() {
    }

    StockTake(UUID locationId, StockTakeStatus status, LocalDate startedOn, Instant closedAt, String notes, String createdByName) {
        this.id = Ids.newId();
        this.locationId = locationId;
        this.status = status;
        this.startedOn = startedOn;
        this.closedAt = closedAt;
        this.notes = notes;
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

    String getNotes() {
        return notes;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }

    String getCreatedByName() {
        return createdByName;
    }
}
