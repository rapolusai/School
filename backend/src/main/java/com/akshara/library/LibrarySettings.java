package com.akshara.library;

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

/**
 * A school's library settings, made on first use. The accession counter is only ever moved by
 * {@link LibrarySetupService#allocateAccessionNumbers} under a row lock, never through this entity, so saving the
 * settings cannot put it back.
 */
@Entity
@Table(schema = "library", name = "settings")
class LibrarySettings extends AssignedIdEntity {

    static final String DEFAULT_PREFIX = "LIB";
    static final int DEFAULT_HOLD_DAYS = 3;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String accessionPrefix;

    @Column(nullable = false, insertable = false, updatable = false)
    private long lastAccessionSeq;

    @Column(nullable = false)
    private int holdDays;

    @Column(nullable = false)
    private boolean remindersEnabled;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LibrarySettings() {
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(String accessionPrefix, int holdDays, boolean remindersEnabled) {
        this.accessionPrefix = accessionPrefix;
        this.holdDays = holdDays;
        this.remindersEnabled = remindersEnabled;
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getAccessionPrefix() {
        return accessionPrefix;
    }

    long getLastAccessionSeq() {
        return lastAccessionSeq;
    }

    int getHoldDays() {
        return holdDays;
    }

    boolean isRemindersEnabled() {
        return remindersEnabled;
    }
}
