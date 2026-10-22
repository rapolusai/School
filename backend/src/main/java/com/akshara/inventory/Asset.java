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

/** A fixed asset of the register. Depreciation is computed, never stored. */
@Entity
@Table(schema = "inventory", name = "asset")
class Asset extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String assetTag;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private UUID categoryId;

    private UUID itemId;

    private UUID locationId;

    private UUID custodianId;

    private String custodianName;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    private long costPaise;

    private UUID vendorId;

    private String serialNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetCondition condition;

    private LocalDate warrantyExpiresOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetStatus status;

    private String disposalReason;

    private String disposalRequestedBy;

    private LocalDate disposedOn;

    private String disposedByName;

    private String notes;

    private LocalDate lastVerifiedOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Asset() {
    }

    Asset(String assetTag, String name, UUID categoryId, UUID itemId, UUID locationId, UUID custodianId, String custodianName, LocalDate purchaseDate, long costPaise, UUID vendorId, String serialNo, AssetCondition condition, LocalDate warrantyExpiresOn, AssetStatus status, String disposalReason, String disposalRequestedBy, LocalDate disposedOn, String disposedByName, String notes, LocalDate lastVerifiedOn) {
        this.id = Ids.newId();
        this.assetTag = assetTag;
        this.name = name;
        this.categoryId = categoryId;
        this.itemId = itemId;
        this.locationId = locationId;
        this.custodianId = custodianId;
        this.custodianName = custodianName;
        this.purchaseDate = purchaseDate;
        this.costPaise = costPaise;
        this.vendorId = vendorId;
        this.serialNo = serialNo;
        this.condition = condition;
        this.warrantyExpiresOn = warrantyExpiresOn;
        this.status = status;
        this.disposalReason = disposalReason;
        this.disposalRequestedBy = disposalRequestedBy;
        this.disposedOn = disposedOn;
        this.disposedByName = disposedByName;
        this.notes = notes;
        this.lastVerifiedOn = lastVerifiedOn;
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

    String getAssetTag() {
        return assetTag;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    UUID getCategoryId() {
        return categoryId;
    }

    void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    UUID getItemId() {
        return itemId;
    }

    void setItemId(UUID itemId) {
        this.itemId = itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    UUID getCustodianId() {
        return custodianId;
    }

    void setCustodianId(UUID custodianId) {
        this.custodianId = custodianId;
    }

    String getCustodianName() {
        return custodianName;
    }

    void setCustodianName(String custodianName) {
        this.custodianName = custodianName;
    }

    LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    long getCostPaise() {
        return costPaise;
    }

    void setCostPaise(long costPaise) {
        this.costPaise = costPaise;
    }

    UUID getVendorId() {
        return vendorId;
    }

    void setVendorId(UUID vendorId) {
        this.vendorId = vendorId;
    }

    String getSerialNo() {
        return serialNo;
    }

    void setSerialNo(String serialNo) {
        this.serialNo = serialNo;
    }

    AssetCondition getCondition() {
        return condition;
    }

    void setCondition(AssetCondition condition) {
        this.condition = condition;
    }

    LocalDate getWarrantyExpiresOn() {
        return warrantyExpiresOn;
    }

    void setWarrantyExpiresOn(LocalDate warrantyExpiresOn) {
        this.warrantyExpiresOn = warrantyExpiresOn;
    }

    AssetStatus getStatus() {
        return status;
    }

    void setStatus(AssetStatus status) {
        this.status = status;
    }

    String getDisposalReason() {
        return disposalReason;
    }

    void setDisposalReason(String disposalReason) {
        this.disposalReason = disposalReason;
    }

    String getDisposalRequestedBy() {
        return disposalRequestedBy;
    }

    void setDisposalRequestedBy(String disposalRequestedBy) {
        this.disposalRequestedBy = disposalRequestedBy;
    }

    LocalDate getDisposedOn() {
        return disposedOn;
    }

    void setDisposedOn(LocalDate disposedOn) {
        this.disposedOn = disposedOn;
    }

    String getDisposedByName() {
        return disposedByName;
    }

    void setDisposedByName(String disposedByName) {
        this.disposedByName = disposedByName;
    }

    String getNotes() {
        return notes;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }

    LocalDate getLastVerifiedOn() {
        return lastVerifiedOn;
    }

    void setLastVerifiedOn(LocalDate lastVerifiedOn) {
        this.lastVerifiedOn = lastVerifiedOn;
    }
}
