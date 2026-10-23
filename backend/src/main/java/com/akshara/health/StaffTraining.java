package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A training a staff member completed (POCSO awareness, first aid), with its expiry. */
@Entity
@Immutable
@Table(schema = "health", name = "staff_training")
class StaffTraining extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrainingKind kind;

    @Column(nullable = false)
    private LocalDate trainedOn;

    private LocalDate expiresOn;

    private String provider;

    @Column(nullable = false)
    private Instant createdAt;

    protected StaffTraining() {
    }

    StaffTraining(UUID userId, TrainingKind kind, LocalDate trainedOn, LocalDate expiresOn, String provider,
            Instant now) {
        this.id = Ids.newId();
        this.userId = userId;
        this.kind = kind;
        this.trainedOn = trainedOn;
        this.expiresOn = expiresOn;
        this.provider = provider;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    TrainingKind getKind() {
        return kind;
    }

    LocalDate getTrainedOn() {
        return trainedOn;
    }

    LocalDate getExpiresOn() {
        return expiresOn;
    }

    String getProvider() {
        return provider;
    }
}
