package com.akshara.accounts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** An approver's limit (null: no limit). People with accounts.approve who are not listed have no limit either. */
@Entity
@Table(schema = "accounts", name = "approver")
class Approver extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String userName;

    private Long limitPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Approver() {
    }

    Approver(UUID userId, String userName, Long limitPaise) {
        this.id = Ids.newId();
        this.userId = userId;
        this.userName = userName;
        this.limitPaise = limitPaise;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void update(String userName, Long limitPaise) {
        this.userName = userName;
        this.limitPaise = limitPaise;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getUserName() {
        return userName;
    }

    Long getLimitPaise() {
        return limitPaise;
    }
}
