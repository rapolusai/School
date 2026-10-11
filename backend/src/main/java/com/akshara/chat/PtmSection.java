package com.akshara.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A section whose parents are invited to a PTM. */
@Entity
@Table(schema = "chat", name = "ptm_section")
class PtmSection extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PtmSection() {
    }

    PtmSection(UUID eventId, UUID sectionId, Instant at) {
        this.id = Ids.newId();
        this.eventId = eventId;
        this.sectionId = sectionId;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getEventId() {
        return eventId;
    }

    UUID getSectionId() {
        return sectionId;
    }
}
