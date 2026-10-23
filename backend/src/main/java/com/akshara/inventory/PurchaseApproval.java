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

import com.akshara.accounts.ApprovalAction;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A decision on a requisition or purchase order, append-only. */
@Entity
@Table(schema = "inventory", name = "purchase_approval")
class PurchaseApproval extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private UUID requisitionId;

    @Column(updatable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ApprovalStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ApprovalAction action;

    @Column(updatable = false)
    private UUID approverId;

    @Column(nullable = false, updatable = false)
    private String approverName;

    @Column(updatable = false)
    private Long limitPaise;

    @Column(updatable = false)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PurchaseApproval() {
    }

    PurchaseApproval(UUID requisitionId, UUID purchaseOrderId, ApprovalStage stage, ApprovalAction action, UUID approverId, String approverName, Long limitPaise, String note) {
        this.id = Ids.newId();
        this.requisitionId = requisitionId;
        this.purchaseOrderId = purchaseOrderId;
        this.stage = stage;
        this.action = action;
        this.approverId = approverId;
        this.approverName = approverName;
        this.limitPaise = limitPaise;
        this.note = note;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    UUID getRequisitionId() {
        return requisitionId;
    }

    UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    ApprovalStage getStage() {
        return stage;
    }

    ApprovalAction getAction() {
        return action;
    }

    UUID getApproverId() {
        return approverId;
    }

    String getApproverName() {
        return approverName;
    }

    Long getLimitPaise() {
        return limitPaise;
    }

    String getNote() {
        return note;
    }
}
