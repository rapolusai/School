package com.akshara.privacy;

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

/**
 * The person parents contact about their data: the published contact and grievance redressal of DPDP Act sections 8(9)
 * and 8(10). One per school, shown on the privacy notice.
 */
@Entity
@Table(schema = "privacy", name = "grievance_officer")
class GrievanceOfficer extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected GrievanceOfficer() {
    }

    GrievanceOfficer(String name, String email, String phone) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(name, email, phone);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getEmail() {
        return email;
    }

    String getPhone() {
        return phone;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
