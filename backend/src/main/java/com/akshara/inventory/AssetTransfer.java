package com.akshara.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A move of an asset between locations or custodians (history). */
@Entity
@Table(schema = "inventory", name = "asset_transfer")
class AssetTransfer extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID assetId;

    @Column(updatable = false)
    private UUID fromLocationId;

    @Column(updatable = false)
    private String fromLocationName;

    @Column(updatable = false)
    private UUID toLocationId;

    @Column(updatable = false)
    private String toLocationName;

    @Column(updatable = false)
    private UUID fromCustodianId;

    @Column(updatable = false)
    private String fromCustodianName;

    @Column(updatable = false)
    private UUID toCustodianId;

    @Column(updatable = false)
    private String toCustodianName;

    @Column(updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private LocalDate movedOn;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AssetTransfer() {
    }

    AssetTransfer(UUID assetId, UUID fromLocationId, String fromLocationName, UUID toLocationId, String toLocationName, UUID fromCustodianId, String fromCustodianName, UUID toCustodianId, String toCustodianName, String reason, LocalDate movedOn, String createdByName) {
        this.id = Ids.newId();
        this.assetId = assetId;
        this.fromLocationId = fromLocationId;
        this.fromLocationName = fromLocationName;
        this.toLocationId = toLocationId;
        this.toLocationName = toLocationName;
        this.fromCustodianId = fromCustodianId;
        this.fromCustodianName = fromCustodianName;
        this.toCustodianId = toCustodianId;
        this.toCustodianName = toCustodianName;
        this.reason = reason;
        this.movedOn = movedOn;
        this.createdByName = createdByName;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getAssetId() {
        return assetId;
    }

    UUID getFromLocationId() {
        return fromLocationId;
    }

    String getFromLocationName() {
        return fromLocationName;
    }

    UUID getToLocationId() {
        return toLocationId;
    }

    String getToLocationName() {
        return toLocationName;
    }

    UUID getFromCustodianId() {
        return fromCustodianId;
    }

    String getFromCustodianName() {
        return fromCustodianName;
    }

    UUID getToCustodianId() {
        return toCustodianId;
    }

    String getToCustodianName() {
        return toCustodianName;
    }

    String getReason() {
        return reason;
    }

    LocalDate getMovedOn() {
        return movedOn;
    }

    String getCreatedByName() {
        return createdByName;
    }
}
