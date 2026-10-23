package com.akshara.assist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A follow-up note on a watch list entry. */
@Entity
@Table(schema = "assist", name = "watch_note")
class WatchNote extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID entryId;

    @Column(nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, updatable = false)
    private String authorName;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected WatchNote() {
    }

    WatchNote(UUID entryId, Actor author, String body, Instant at) {
        this.id = Ids.newId();
        this.entryId = entryId;
        this.authorId = author.id();
        this.authorName = author.name();
        this.body = body;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getEntryId() {
        return entryId;
    }

    String getAuthorName() {
        return authorName;
    }

    String getBody() {
        return body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
