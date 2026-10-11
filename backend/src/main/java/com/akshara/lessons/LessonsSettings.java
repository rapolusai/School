package com.akshara.lessons;

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

/** A school's lessons settings: whether families are told about new online tests (off by default). */
@Entity
@Table(schema = "lessons", name = "settings")
class LessonsSettings extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean testNotificationsEnabled;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LessonsSettings() {
    }

    LessonsSettings(boolean testNotificationsEnabled) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.testNotificationsEnabled = testNotificationsEnabled;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(boolean testNotificationsEnabled) {
        this.testNotificationsEnabled = testNotificationsEnabled;
    }

    @Override
    public UUID getId() {
        return id;
    }

    boolean isTestNotificationsEnabled() {
        return testNotificationsEnabled;
    }
}
