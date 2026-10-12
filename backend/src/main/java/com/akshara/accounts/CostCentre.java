package com.akshara.accounts;

import java.time.Instant;
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

/** An optional cost centre an expense is charged to: a department, a campus, a project. */
@Entity
@Table(schema = "accounts", name = "cost_centre")
class CostCentre extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CostCentre() {
    }

    CostCentre(String code, String name) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void update(String name, boolean active) {
        this.name = name;
        this.active = active;
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

    boolean isActive() {
        return active;
    }
}
