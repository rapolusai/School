package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A fee head a concession applies to. */
@Entity
@Table(schema = "fees", name = "concession_head")
class ConcessionHead extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID concessionId;

    @Column(nullable = false, updatable = false)
    private UUID headId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ConcessionHead() {
    }

    ConcessionHead(UUID concessionId, UUID headId) {
        this.id = Ids.newId();
        this.concessionId = concessionId;
        this.headId = headId;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getConcessionId() {
        return concessionId;
    }

    UUID getHeadId() {
        return headId;
    }
}
