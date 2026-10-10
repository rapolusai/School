package com.akshara.privacy;

import java.time.Instant;
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

/** One step on a data request's timeline: who did what, when, with the message if there was one. Never changed. */
@Entity
@Immutable
@Table(schema = "privacy", name = "request_event")
class RequestEvent extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestEventKind kind;

    private UUID actorId;

    private String actorName;

    private String body;

    @Column(nullable = false)
    private Instant at;

    protected RequestEvent() {
    }

    RequestEvent(UUID requestId, RequestEventKind kind, UUID actorId, String actorName, String body, Instant at) {
        this.id = Ids.newId();
        this.requestId = requestId;
        this.kind = kind;
        this.actorId = actorId;
        this.actorName = actorName;
        this.body = body;
        this.at = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRequestId() {
        return requestId;
    }

    RequestEventKind getKind() {
        return kind;
    }

    UUID getActorId() {
        return actorId;
    }

    String getActorName() {
        return actorName;
    }

    String getBody() {
        return body;
    }

    Instant getAt() {
        return at;
    }
}
