package com.akshara.homework;

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

/** A school's homework settings. Without a row, reminders to parents are off. */
@Entity
@Table(schema = "homework", name = "settings")
class HomeworkSettings extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean remindersEnabled;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected HomeworkSettings() {
    }

    HomeworkSettings(boolean remindersEnabled) {
        this.id = Ids.newId();
        this.remindersEnabled = remindersEnabled;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(boolean remindersEnabled) {
        this.remindersEnabled = remindersEnabled;
    }

    @Override
    public UUID getId() {
        return id;
    }

    boolean isRemindersEnabled() {
        return remindersEnabled;
    }
}
