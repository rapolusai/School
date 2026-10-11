package com.akshara.exams;

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
 * A school's exam settings: whether report cards show ranks (off by default; CBSE discourages them), the highest
 * class level that gets the NEP 2020 holistic progress card (Class 5 by default), and the co-scholastic scheme.
 */
@Entity
@Table(schema = "exams", name = "settings")
class ExamSettings extends AssignedIdEntity {

    static final int DEFAULT_HPC_UP_TO = 5;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean ranksEnabled;

    @Column(name = "hpc_up_to_level", nullable = false)
    private int hpcUpToLevel;

    private UUID coScholasticSchemeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ExamSettings() {
    }

    ExamSettings(boolean ranksEnabled, int hpcUpToLevel, UUID coScholasticSchemeId) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(ranksEnabled, hpcUpToLevel, coScholasticSchemeId);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(boolean ranksEnabled, int hpcUpToLevel, UUID coScholasticSchemeId) {
        this.ranksEnabled = ranksEnabled;
        this.hpcUpToLevel = hpcUpToLevel;
        this.coScholasticSchemeId = coScholasticSchemeId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    boolean isRanksEnabled() {
        return ranksEnabled;
    }

    int getHpcUpToLevel() {
        return hpcUpToLevel;
    }

    UUID getCoScholasticSchemeId() {
        return coScholasticSchemeId;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
