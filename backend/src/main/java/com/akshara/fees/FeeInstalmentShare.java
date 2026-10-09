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

/** How much of one head is due in one instalment. Replaced as a whole when the structure is saved. */
@Entity
@Table(schema = "fees", name = "fee_instalment_share")
class FeeInstalmentShare extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID instalmentId;

    @Column(nullable = false, updatable = false)
    private UUID headId;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false)
    private Instant createdAt;

    protected FeeInstalmentShare() {
    }

    FeeInstalmentShare(UUID instalmentId, UUID headId, long amountPaise) {
        this.id = Ids.newId();
        this.instalmentId = instalmentId;
        this.headId = headId;
        this.amountPaise = amountPaise;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getInstalmentId() {
        return instalmentId;
    }

    UUID getHeadId() {
        return headId;
    }

    long getAmountPaise() {
        return amountPaise;
    }
}
