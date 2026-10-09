package com.akshara.admissions;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;
import com.akshara.students.GuardianRelation;

/** A parent or guardian named on an application. Replaced as a whole when the application is edited. */
@Entity
@Table(schema = "admissions", name = "application_guardian")
class ApplicationGuardian extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID applicationId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private GuardianRelation relation;

    /** Ten-digit Indian mobile number without +91. */
    @Column(nullable = false, updatable = false)
    private String phone;

    @Column(updatable = false)
    private String email;

    @Column(name = "is_primary", nullable = false, updatable = false)
    private boolean primary;

    @Column(nullable = false, updatable = false)
    private short position;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ApplicationGuardian() {
    }

    ApplicationGuardian(UUID applicationId, String name, GuardianRelation relation, String phone, String email,
            boolean primary, int position) {
        this.id = Ids.newId();
        this.applicationId = applicationId;
        this.name = name;
        this.relation = relation;
        this.phone = phone;
        this.email = email;
        this.primary = primary;
        this.position = (short) position;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    String getName() {
        return name;
    }

    GuardianRelation getRelation() {
        return relation;
    }

    String getPhone() {
        return phone;
    }

    String getEmail() {
        return email;
    }

    boolean isPrimary() {
        return primary;
    }

    int getPosition() {
        return position;
    }
}
