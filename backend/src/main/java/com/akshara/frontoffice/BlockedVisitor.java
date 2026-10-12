package com.akshara.frontoffice;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** Someone the school will not let in, with the reason. Matched at check-in by phone number or name. */
@Entity
@Table(schema = "frontoffice", name = "blocked_visitor")
class BlockedVisitor extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Column(nullable = false)
    private String reason;

    @Column(updatable = false)
    private String addedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected BlockedVisitor() {
    }

    BlockedVisitor(String name, String phone, String reason, Actor by, Instant now) {
        this.id = Ids.newId();
        this.name = name;
        this.phone = phone;
        this.reason = reason;
        this.addedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, String phone, String reason) {
        this.name = name;
        this.phone = phone;
        this.reason = reason;
    }

    /** True when the phone matches, or (without a phone to compare) the name matches ignoring case and spaces. */
    boolean matches(String visitorName, String visitorPhone) {
        if (phone != null && visitorPhone != null) {
            return phone.equals(visitorPhone);
        }
        return FrontOfficeText.sameName(name, visitorName);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getPhone() {
        return phone;
    }

    String getReason() {
        return reason;
    }

    String getAddedByName() {
        return addedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
