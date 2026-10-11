package com.akshara.transport;

import java.time.Instant;
import java.time.LocalDate;
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
 * A member of staff who drives or accompanies a bus. Their name and contact details stay in the staff module; only
 * the licence (for drivers) and the police verification date are kept here.
 */
@Entity
@Table(schema = "transport", name = "crew_member")
class CrewMember extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrewKind kind;

    private String licenceNo;

    private LocalDate licenceExpiry;

    private LocalDate policeVerifiedOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CrewMember() {
    }

    CrewMember(UUID userId, CrewKind kind, String licenceNo, LocalDate licenceExpiry, LocalDate policeVerifiedOn) {
        this.id = Ids.newId();
        this.userId = userId;
        update(kind, licenceNo, licenceExpiry, policeVerifiedOn);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(CrewKind kind, String licenceNo, LocalDate licenceExpiry, LocalDate policeVerifiedOn) {
        this.kind = kind;
        this.licenceNo = licenceNo;
        this.licenceExpiry = licenceExpiry;
        this.policeVerifiedOn = policeVerifiedOn;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    CrewKind getKind() {
        return kind;
    }

    String getLicenceNo() {
        return licenceNo;
    }

    LocalDate getLicenceExpiry() {
        return licenceExpiry;
    }

    LocalDate getPoliceVerifiedOn() {
        return policeVerifiedOn;
    }
}
