package com.akshara.library;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A copy seen on the shelf during a stock check, by whom and when. Scanning it again changes nothing. */
@Entity
@Table(schema = "library", name = "stock_check_item")
class StockCheckItem extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID stockCheckId;

    @Column(nullable = false, updatable = false)
    private UUID copyId;

    @Column(nullable = false, updatable = false)
    private Instant seenAt;

    @Column(updatable = false)
    private UUID seenById;

    protected StockCheckItem() {
    }

    StockCheckItem(UUID stockCheckId, UUID copyId, Instant seenAt, UUID seenById) {
        this.id = Ids.newId();
        this.stockCheckId = stockCheckId;
        this.copyId = copyId;
        this.seenAt = seenAt;
        this.seenById = seenById;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStockCheckId() {
        return stockCheckId;
    }

    UUID getCopyId() {
        return copyId;
    }

    Instant getSeenAt() {
        return seenAt;
    }
}
