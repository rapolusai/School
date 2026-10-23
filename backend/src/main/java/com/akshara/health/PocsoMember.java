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

/** A member of the POCSO committee: a staff sign-in, which thereby may read the complaints register. */
@Entity
@Immutable
@Table(schema = "health", name = "pocso_member")
class PocsoMember extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommitteeRole role;

    private LocalDate appointedOn;

    @Column(nullable = false)
    private Instant createdAt;

    protected PocsoMember() {
    }

    PocsoMember(UUID userId, String name, CommitteeRole role, LocalDate appointedOn, Instant now) {
        this.id = Ids.newId();
        this.userId = userId;
        this.name = name;
        this.role = role;
        this.appointedOn = appointedOn;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getName() {
        return name;
    }

    CommitteeRole getRole() {
        return role;
    }

    LocalDate getAppointedOn() {
        return appointedOn;
    }
}
