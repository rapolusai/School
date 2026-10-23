package com.akshara.health;

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

/** A staff member's police verification: one row per person, created when it is first recorded. */
@Entity
@Table(schema = "health", name = "staff_verification")
class StaffVerification extends AssignedIdEntity {

    record Details(VerificationStatus status, LocalDate requestedOn, LocalDate clearedOn, String referenceNo,
            LocalDate expiresOn, String note) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;

    private LocalDate requestedOn;

    private LocalDate clearedOn;

    private String referenceNo;

    private LocalDate expiresOn;

    private String note;

    private String updatedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffVerification() {
    }

    StaffVerification(UUID userId, Instant now) {
        this.id = Ids.newId();
        this.userId = userId;
        this.status = VerificationStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d, String byName) {
        this.status = d.status();
        this.requestedOn = d.requestedOn();
        this.clearedOn = d.clearedOn();
        this.referenceNo = d.referenceNo();
        this.expiresOn = d.expiresOn();
        this.note = d.note();
        this.updatedByName = byName;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    VerificationStatus getStatus() {
        return status;
    }

    LocalDate getRequestedOn() {
        return requestedOn;
    }

    LocalDate getClearedOn() {
        return clearedOn;
    }

    String getReferenceNo() {
        return referenceNo;
    }

    LocalDate getExpiresOn() {
        return expiresOn;
    }

    String getNote() {
        return note;
    }

    String getUpdatedByName() {
        return updatedByName;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
