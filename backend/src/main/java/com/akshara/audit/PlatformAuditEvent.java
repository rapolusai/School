package com.akshara.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One entry in the Super Admin's own trail across schools ({@code audit.platform_event}): Super Admin sign-ins and
 * sign-outs, and subscription, invoice and school status changes with the school they concern ({@code tenantId}, null
 * for platform-only events). Rows are never updated or deleted.
 */
@Entity
@Immutable
@Table(schema = "audit", name = "platform_event")
public class PlatformAuditEvent extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant at;

    private UUID actorId;

    private String actorName;

    @Column(nullable = false)
    private boolean platformAdmin;

    @Column(nullable = false)
    private String action;

    private UUID tenantId;

    private String entityType;

    private String entityId;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String details;

    private String ip;

    protected PlatformAuditEvent() {
    }

    PlatformAuditEvent(UUID actorId, String actorName, boolean platformAdmin, String action, UUID tenantId,
            String entityType, String entityId, String details, String ip) {
        this.id = Ids.newId();
        this.at = Instant.now();
        this.actorId = actorId;
        this.actorName = actorName;
        this.platformAdmin = platformAdmin;
        this.action = action;
        this.tenantId = tenantId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.ip = ip;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public String getAction() {
        return action;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getDetails() {
        return details;
    }

    public String getIp() {
        return ip;
    }
}
