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

/** A line of a goods receipt. */
@Entity
@Table(schema = "inventory", name = "goods_receipt_line")
class GoodsReceiptLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID goodsReceiptId;

    @Column(nullable = false, updatable = false)
    private UUID poLineId;

    @Column(updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private int quantity;

    @Column(updatable = false)
    private long unitPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected GoodsReceiptLine() {
    }

    GoodsReceiptLine(UUID goodsReceiptId, UUID poLineId, UUID itemId, int quantity, long unitPaise) {
        this.id = Ids.newId();
        this.goodsReceiptId = goodsReceiptId;
        this.poLineId = poLineId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.unitPaise = unitPaise;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getGoodsReceiptId() {
        return goodsReceiptId;
    }

    UUID getPoLineId() {
        return poLineId;
    }

    UUID getItemId() {
        return itemId;
    }

    int getQuantity() {
        return quantity;
    }

    long getUnitPaise() {
        return unitPaise;
    }
}
