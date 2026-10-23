package com.akshara.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A line of the stock ledger: quantity is positive, direction +1 adds to stock and -1 takes from it. */
@Entity
@Table(schema = "inventory", name = "stock_movement")
class StockMovement extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MovementKind kind;

    @Column(updatable = false)
    private int direction;

    @Column(updatable = false)
    private int quantity;

    @Column(updatable = false)
    private Long unitCostPaise;

    @Column(nullable = false, updatable = false)
    private LocalDate movedOn;

    @Column(updatable = false)
    private UUID departmentId;

    @Column(updatable = false)
    private String departmentName;

    @Column(updatable = false)
    private UUID issuedToId;

    @Column(updatable = false)
    private String issuedToName;

    @Column(updatable = false)
    private String purpose;

    @Column(updatable = false)
    private UUID purchaseOrderId;

    @Column(updatable = false)
    private UUID goodsReceiptId;

    @Column(updatable = false)
    private UUID stockTakeId;

    @Column(updatable = false)
    private UUID createdById;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    StockMovement(UUID itemId, UUID locationId, MovementKind kind, int direction, int quantity, Long unitCostPaise, LocalDate movedOn, UUID departmentId, String departmentName, UUID issuedToId, String issuedToName, String purpose, UUID purchaseOrderId, UUID goodsReceiptId, UUID stockTakeId, UUID createdById, String createdByName) {
        this.id = Ids.newId();
        this.itemId = itemId;
        this.locationId = locationId;
        this.kind = kind;
        this.direction = direction;
        this.quantity = quantity;
        this.unitCostPaise = unitCostPaise;
        this.movedOn = movedOn;
        this.departmentId = departmentId;
        this.departmentName = departmentName;
        this.issuedToId = issuedToId;
        this.issuedToName = issuedToName;
        this.purpose = purpose;
        this.purchaseOrderId = purchaseOrderId;
        this.goodsReceiptId = goodsReceiptId;
        this.stockTakeId = stockTakeId;
        this.createdById = createdById;
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

    UUID getItemId() {
        return itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    MovementKind getKind() {
        return kind;
    }

    int getDirection() {
        return direction;
    }

    int getQuantity() {
        return quantity;
    }

    Long getUnitCostPaise() {
        return unitCostPaise;
    }

    LocalDate getMovedOn() {
        return movedOn;
    }

    UUID getDepartmentId() {
        return departmentId;
    }

    String getDepartmentName() {
        return departmentName;
    }

    UUID getIssuedToId() {
        return issuedToId;
    }

    String getIssuedToName() {
        return issuedToName;
    }

    String getPurpose() {
        return purpose;
    }

    UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    UUID getGoodsReceiptId() {
        return goodsReceiptId;
    }

    UUID getStockTakeId() {
        return stockTakeId;
    }

    UUID getCreatedById() {
        return createdById;
    }

    String getCreatedByName() {
        return createdByName;
    }
}
