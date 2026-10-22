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

/** A vendor's quote on an RFQ, recorded by hand. */
@Entity
@Table(schema = "inventory", name = "quote")
class Quote extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID rfqId;

    @Column(nullable = false, updatable = false)
    private UUID vendorId;

    @Column(nullable = false, updatable = false)
    private String vendorName;

    @Column(updatable = false)
    private long amountPaise;

    @Column(updatable = false)
    private Integer deliveryDays;

    @Column(updatable = false)
    private LocalDate validUntil;

    @Column(updatable = false)
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Quote() {
    }

    Quote(UUID rfqId, UUID vendorId, String vendorName, long amountPaise, Integer deliveryDays, LocalDate validUntil, String notes) {
        this.id = Ids.newId();
        this.rfqId = rfqId;
        this.vendorId = vendorId;
        this.vendorName = vendorName;
        this.amountPaise = amountPaise;
        this.deliveryDays = deliveryDays;
        this.validUntil = validUntil;
        this.notes = notes;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getRfqId() {
        return rfqId;
    }

    UUID getVendorId() {
        return vendorId;
    }

    String getVendorName() {
        return vendorName;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    Integer getDeliveryDays() {
        return deliveryDays;
    }

    LocalDate getValidUntil() {
        return validUntil;
    }

    String getNotes() {
        return notes;
    }
}
