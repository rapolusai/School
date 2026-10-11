package com.akshara.transport;

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

/** Which bus messages a school's parents get. Made on first use with the defaults. */
@Entity
@Table(schema = "transport", name = "settings")
class TransportSettings extends AssignedIdEntity {

    static final boolean DEFAULT_BOARDED = true;
    static final boolean DEFAULT_DROPPED = true;
    static final boolean DEFAULT_APPROACHING = false;
    static final boolean DEFAULT_DELAY = true;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean boardedAlerts;

    @Column(nullable = false)
    private boolean droppedAlerts;

    @Column(nullable = false)
    private boolean approachingAlerts;

    @Column(nullable = false)
    private boolean delayAlerts;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TransportSettings() {
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(boolean boarded, boolean dropped, boolean approaching, boolean delay) {
        this.boardedAlerts = boarded;
        this.droppedAlerts = dropped;
        this.approachingAlerts = approaching;
        this.delayAlerts = delay;
    }

    @Override
    public UUID getId() {
        return id;
    }

    boolean isBoardedAlerts() {
        return boardedAlerts;
    }

    boolean isDroppedAlerts() {
        return droppedAlerts;
    }

    boolean isApproachingAlerts() {
        return approachingAlerts;
    }

    boolean isDelayAlerts() {
        return delayAlerts;
    }
}
