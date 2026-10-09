package com.akshara.staff;

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

/** A group of staff (Primary, Science, Administration…). Its head approves the leave of its staff. */
@Entity
@Table(schema = "staff", name = "department")
class Department extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private UUID headUserId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Department() {
    }

    Department(String name, UUID headUserId) {
        this.id = Ids.newId();
        this.name = name;
        this.headUserId = headUserId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, UUID headUserId) {
        this.name = name;
        this.headUserId = headUserId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    UUID getHeadUserId() {
        return headUserId;
    }
}
