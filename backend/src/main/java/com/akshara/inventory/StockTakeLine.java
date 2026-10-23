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

/** One item of a stock take. */
@Entity
@Table(schema = "inventory", name = "stock_take_line")
class StockTakeLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID stockTakeId;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private int expectedQty;

    private Integer countedQty;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StockTakeLine() {
    }

    StockTakeLine(UUID stockTakeId, UUID itemId, int expectedQty, Integer countedQty) {
        this.id = Ids.newId();
        this.stockTakeId = stockTakeId;
        this.itemId = itemId;
        this.expectedQty = expectedQty;
        this.countedQty = countedQty;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getStockTakeId() {
        return stockTakeId;
    }

    UUID getItemId() {
        return itemId;
    }

    int getExpectedQty() {
        return expectedQty;
    }

    Integer getCountedQty() {
        return countedQty;
    }

    void setCountedQty(Integer countedQty) {
        this.countedQty = countedQty;
    }
}
