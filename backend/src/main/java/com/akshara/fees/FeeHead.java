package com.akshara.fees;

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

/** Something a school charges for: Tuition, Admission, Annual charges, Exam, Transport, Lab… */
@Entity
@Table(schema = "fees", name = "fee_head")
class FeeHead extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeeHeadKind kind;

    @Column(nullable = false)
    private boolean oneTime;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FeeHead() {
    }

    FeeHead(String name, FeeHeadKind kind, boolean oneTime, int displayOrder) {
        this.id = Ids.newId();
        this.name = name;
        this.kind = kind;
        this.oneTime = oneTime;
        this.displayOrder = displayOrder;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, FeeHeadKind kind, boolean oneTime, boolean active) {
        this.name = name;
        this.kind = kind;
        this.oneTime = oneTime;
        this.active = active;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    FeeHeadKind getKind() {
        return kind;
    }

    boolean isOneTime() {
        return oneTime;
    }

    int getDisplayOrder() {
        return displayOrder;
    }

    boolean isActive() {
        return active;
    }
}
