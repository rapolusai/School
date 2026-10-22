package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One line of the infirmary's medicine stock. */
@Entity
@Table(schema = "health", name = "medicine")
class Medicine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String unit;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int reorderLevel;

    private LocalDate expiresOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Medicine() {
    }

    Medicine(String name, String unit, int quantity, int reorderLevel, LocalDate expiresOn, Instant now) {
        this.id = Ids.newId();
        this.createdAt = now;
        this.updatedAt = now;
        apply(name, unit, quantity, reorderLevel, expiresOn);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(String name, String unit, int quantity, int reorderLevel, LocalDate expiresOn) {
        this.name = name;
        this.unit = unit;
        this.quantity = quantity;
        this.reorderLevel = reorderLevel;
        this.expiresOn = expiresOn;
    }

    /** Takes {@code amount} from the stock; false when there is not enough. */
    boolean take(int amount) {
        if (amount > quantity) {
            return false;
        }
        quantity -= amount;
        return true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getUnit() {
        return unit;
    }

    int getQuantity() {
        return quantity;
    }

    int getReorderLevel() {
        return reorderLevel;
    }

    LocalDate getExpiresOn() {
        return expiresOn;
    }
}
