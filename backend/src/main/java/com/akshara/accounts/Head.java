package com.akshara.accounts;

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

/**
 * An income or expense head in the chart, one level deep (a head with a parent is a sub-head). Income heads mapped
 * from the fees module carry the fee head's id; {@code systemCode} marks the built-in heads the module itself posts
 * to (FEES, SALARIES, PETTY_CASH …).
 */
@Entity
@Table(schema = "accounts", name = "head")
class Head extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private HeadKind kind;

    private UUID parentId;

    @Column(nullable = false)
    private boolean gstApplicable;

    private UUID feeHeadId;

    @Column(updatable = false)
    private String systemCode;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Head() {
    }

    Head(String code, String name, HeadKind kind, UUID parentId, boolean gstApplicable, UUID feeHeadId,
            String systemCode, int displayOrder) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.parentId = parentId;
        this.gstApplicable = gstApplicable;
        this.feeHeadId = feeHeadId;
        this.systemCode = systemCode;
        this.displayOrder = displayOrder;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void update(String name, UUID parentId, boolean gstApplicable, boolean active, int displayOrder) {
        this.name = name;
        this.parentId = parentId;
        this.gstApplicable = gstApplicable;
        this.active = active;
        this.displayOrder = displayOrder;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    HeadKind getKind() {
        return kind;
    }

    UUID getParentId() {
        return parentId;
    }

    boolean isGstApplicable() {
        return gstApplicable;
    }

    UUID getFeeHeadId() {
        return feeHeadId;
    }

    String getSystemCode() {
        return systemCode;
    }

    boolean isActive() {
        return active;
    }

    int getDisplayOrder() {
        return displayOrder;
    }
}
