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

/** A first-aid kit at a location and its last check: complete or not, what is missing, the earliest expiry. */
@Entity
@Table(schema = "health", name = "first_aid_kit")
class FirstAidKit extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String location;

    private LocalDate checkedOn;

    @Column(nullable = false)
    private boolean complete;

    private LocalDate earliestExpiry;

    private String missingItems;

    private String checkedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FirstAidKit() {
    }

    FirstAidKit(String location, Instant now) {
        this.id = Ids.newId();
        this.location = location;
        this.complete = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void rename(String location) {
        this.location = location;
    }

    void check(LocalDate on, boolean complete, LocalDate earliestExpiry, String missingItems, String byName) {
        this.checkedOn = on;
        this.complete = complete;
        this.earliestExpiry = earliestExpiry;
        this.missingItems = missingItems;
        this.checkedByName = byName;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getLocation() {
        return location;
    }

    LocalDate getCheckedOn() {
        return checkedOn;
    }

    boolean isComplete() {
        return complete;
    }

    LocalDate getEarliestExpiry() {
        return earliestExpiry;
    }

    String getMissingItems() {
        return missingItems;
    }

    String getCheckedByName() {
        return checkedByName;
    }
}
