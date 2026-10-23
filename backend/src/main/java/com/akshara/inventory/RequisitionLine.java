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

/** A line of a requisition. */
@Entity
@Table(schema = "inventory", name = "requisition_line")
class RequisitionLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID requisitionId;

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
    private long estimatedUnitPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RequisitionLine() {
    }

    RequisitionLine(UUID requisitionId, int lineNo, UUID itemId, String description, int quantity, String unit, long estimatedUnitPaise) {
        this.id = Ids.newId();
        this.requisitionId = requisitionId;
        this.lineNo = lineNo;
        this.itemId = itemId;
        this.description = description;
        this.quantity = quantity;
        this.unit = unit;
        this.estimatedUnitPaise = estimatedUnitPaise;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getRequisitionId() {
        return requisitionId;
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

    long getEstimatedUnitPaise() {
        return estimatedUnitPaise;
    }
}
