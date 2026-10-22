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

/** A receipt of goods against a purchase order. */
@Entity
@Table(schema = "inventory", name = "goods_receipt")
class GoodsReceipt extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @Column(nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(updatable = false)
    private UUID locationId;

    @Column(updatable = false)
    private String invoiceNumber;

    @Column(updatable = false)
    private String notes;

    private long totalPaise;

    @Column(updatable = false)
    private UUID receivedById;

    @Column(nullable = false, updatable = false)
    private String receivedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected GoodsReceipt() {
    }

    GoodsReceipt(String number, UUID purchaseOrderId, LocalDate receivedOn, UUID locationId, String invoiceNumber, String notes, long totalPaise, UUID receivedById, String receivedByName) {
        this.id = Ids.newId();
        this.number = number;
        this.purchaseOrderId = purchaseOrderId;
        this.receivedOn = receivedOn;
        this.locationId = locationId;
        this.invoiceNumber = invoiceNumber;
        this.notes = notes;
        this.totalPaise = totalPaise;
        this.receivedById = receivedById;
        this.receivedByName = receivedByName;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getNumber() {
        return number;
    }

    UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    LocalDate getReceivedOn() {
        return receivedOn;
    }

    UUID getLocationId() {
        return locationId;
    }

    String getInvoiceNumber() {
        return invoiceNumber;
    }

    String getNotes() {
        return notes;
    }

    long getTotalPaise() {
        return totalPaise;
    }

    void setTotalPaise(long totalPaise) {
        this.totalPaise = totalPaise;
    }

    UUID getReceivedById() {
        return receivedById;
    }

    String getReceivedByName() {
        return receivedByName;
    }
}
