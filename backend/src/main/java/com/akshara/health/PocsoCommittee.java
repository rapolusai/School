package com.akshara.health;

import java.time.Instant;
import java.time.LocalDate;
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

/** The school's POCSO committee: one row per school with the formation date. */
@Entity
@Table(schema = "health", name = "pocso_committee")
class PocsoCommittee extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private LocalDate formedOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PocsoCommittee() {
    }

    PocsoCommittee(Instant now) {
        this.id = Ids.newId();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void setFormedOn(LocalDate formedOn) {
        this.formedOn = formedOn;
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getFormedOn() {
        return formedOn;
    }
}
