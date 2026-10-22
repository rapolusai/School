package com.akshara.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A request for quotation to up to three vendors. */
@Entity
@Table(schema = "inventory", name = "rfq")
class Rfq extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(updatable = false)
    private UUID requisitionId;

    @Column(nullable = false, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RfqStatus status;

    private UUID selectedQuoteId;

    private String selectionReason;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Rfq() {
    }

    Rfq(String number, UUID requisitionId, String title, RfqStatus status, UUID selectedQuoteId, String selectionReason, String createdByName) {
        this.id = Ids.newId();
        this.number = number;
        this.requisitionId = requisitionId;
        this.title = title;
        this.status = status;
        this.selectedQuoteId = selectedQuoteId;
        this.selectionReason = selectionReason;
        this.createdByName = createdByName;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getNumber() {
        return number;
    }

    UUID getRequisitionId() {
        return requisitionId;
    }

    String getTitle() {
        return title;
    }

    RfqStatus getStatus() {
        return status;
    }

    void setStatus(RfqStatus status) {
        this.status = status;
    }

    UUID getSelectedQuoteId() {
        return selectedQuoteId;
    }

    void setSelectedQuoteId(UUID selectedQuoteId) {
        this.selectedQuoteId = selectedQuoteId;
    }

    String getSelectionReason() {
        return selectionReason;
    }

    void setSelectionReason(String selectionReason) {
        this.selectionReason = selectionReason;
    }

    String getCreatedByName() {
        return createdByName;
    }
}
