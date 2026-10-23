package com.akshara.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One asset of a verification round and what was found. */
@Entity
@Table(schema = "inventory", name = "verification_item")
class VerificationItem extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID roundId;

    @Column(nullable = false, updatable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    private VerificationResult result;

    private String note;

    private Instant checkedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected VerificationItem() {
    }

    VerificationItem(UUID roundId, UUID assetId, VerificationResult result, String note, Instant checkedAt) {
        this.id = Ids.newId();
        this.roundId = roundId;
        this.assetId = assetId;
        this.result = result;
        this.note = note;
        this.checkedAt = checkedAt;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getRoundId() {
        return roundId;
    }

    UUID getAssetId() {
        return assetId;
    }

    VerificationResult getResult() {
        return result;
    }

    void setResult(VerificationResult result) {
        this.result = result;
    }

    String getNote() {
        return note;
    }

    void setNote(String note) {
        this.note = note;
    }

    Instant getCheckedAt() {
        return checkedAt;
    }

    void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }
}
