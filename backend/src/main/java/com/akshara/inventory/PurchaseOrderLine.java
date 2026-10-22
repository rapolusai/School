package com.akshara.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A line of a purchase order with its GST rate in basis points. */
@Entity
@Table(schema = "inventory", name = "purchase_order_line")
class PurchaseOrderLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @Column(updatable = false)
    private int lineNo;

    @Column(updatable = false)
    private UUID itemId;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false)
    private String unit;

    @Column(updatable = false)
    private long unitPaise;

    @Column(updatable = false)
    private int gstRateBp;

    @Column(updatable = false)
    private long amountPaise;

    @Column(updatable = false)
    private long taxPaise;

    private int receivedQty;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PurchaseOrderLine() {
    }

    PurchaseOrderLine(UUID purchaseOrderId, int lineNo, UUID itemId, String description, int quantity, String unit, long unitPaise, int gstRateBp, long amountPaise, long taxPaise, int receivedQty) {
        this.id = Ids.newId();
        this.purchaseOrderId = purchaseOrderId;
        this.lineNo = lineNo;
        this.itemId = itemId;
        this.description = description;
        this.quantity = quantity;
        this.unit = unit;
        this.unitPaise = unitPaise;
        this.gstRateBp = gstRateBp;
        this.amountPaise = amountPaise;
        this.taxPaise = taxPaise;
        this.receivedQty = receivedQty;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    int getLineNo() {
        return lineNo;
    }

    UUID getItemId() {
        return itemId;
    }

    String getDescription() {
        return description;
    }

    int getQuantity() {
        return quantity;
    }

    String getUnit() {
        return unit;
    }

    long getUnitPaise() {
        return unitPaise;
    }

    int getGstRateBp() {
        return gstRateBp;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    long getTaxPaise() {
        return taxPaise;
    }

    int getReceivedQty() {
        return receivedQty;
    }

    void setReceivedQty(int receivedQty) {
        this.receivedQty = receivedQty;
    }
}
