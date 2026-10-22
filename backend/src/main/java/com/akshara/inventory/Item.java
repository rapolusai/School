package com.akshara.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** An item of the master; the current stock is the sum of its movements. */
@Entity
@Table(schema = "inventory", name = "item")
class Item extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemKind kind;

    private int reorderLevel;

    private UUID preferredVendorId;

    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Item() {
    }

    Item(String name, UUID categoryId, String unit, ItemKind kind, int reorderLevel, UUID preferredVendorId, boolean active) {
        this.id = Ids.newId();
        this.name = name;
        this.categoryId = categoryId;
        this.unit = unit;
        this.kind = kind;
        this.reorderLevel = reorderLevel;
        this.preferredVendorId = preferredVendorId;
        this.active = active;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    UUID getCategoryId() {
        return categoryId;
    }

    void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    String getUnit() {
        return unit;
    }

    void setUnit(String unit) {
        this.unit = unit;
    }

    ItemKind getKind() {
        return kind;
    }

    void setKind(ItemKind kind) {
        this.kind = kind;
    }

    int getReorderLevel() {
        return reorderLevel;
    }

    void setReorderLevel(int reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    UUID getPreferredVendorId() {
        return preferredVendorId;
    }

    void setPreferredVendorId(UUID preferredVendorId) {
        this.preferredVendorId = preferredVendorId;
    }

    boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }
}
