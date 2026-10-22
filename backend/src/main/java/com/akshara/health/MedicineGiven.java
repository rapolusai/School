package com.akshara.health;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** Medicine given during an infirmary visit, taken from the stock. */
@Entity
@Immutable
@Table(schema = "health", name = "medicine_given")
class MedicineGiven extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID visitId;

    @Column(nullable = false)
    private UUID medicineId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private Instant createdAt;

    protected MedicineGiven() {
    }

    MedicineGiven(UUID visitId, UUID medicineId, int quantity, Instant now) {
        this.id = Ids.newId();
        this.visitId = visitId;
        this.medicineId = medicineId;
        this.quantity = quantity;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getVisitId() {
        return visitId;
    }

    UUID getMedicineId() {
        return medicineId;
    }

    int getQuantity() {
        return quantity;
    }
}
