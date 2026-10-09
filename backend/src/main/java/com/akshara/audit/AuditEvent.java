package com.akshara.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.Immutable;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One entry in a school's audit trail. Rows are never updated or deleted. */
@Entity
@Immutable
@Table(schema = "audit", name = "audit_event")
public class AuditEvent extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private Instant at;

    private UUID actorId;

    private String actorName;

    @Column(nullable = false)
    private String action;

    private String entityType;

    private String entityId;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String details;

    private String ip;

    protected AuditEvent() {
    }

    AuditEvent(UUID actorId, String actorName, String action, String entityType, String entityId, String details,
            String ip) {
        this.id = Ids.newId();
        this.at = Instant.now();
        this.actorId = actorId;
        this.actorName = actorName;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.ip = ip;
    }

    public UUID getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public String getActorName() {
        return actorName;
    }

    public String getAction() {
        return action;
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
}
