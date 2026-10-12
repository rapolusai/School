package com.akshara.accounts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One decision on an expense: approved, passed on (limit too small) or rejected. Append only. */
@Entity
@Table(schema = "accounts", name = "expense_approval")
class ExpenseApproval extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID expenseId;

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

    protected ExpenseApproval() {
    }

    ExpenseApproval(UUID expenseId, ApprovalAction action, Actor by, Long limitPaise, String note, Instant at) {
        this.id = Ids.newId();
        this.expenseId = expenseId;
        this.action = action;
        this.approverId = by.id();
        this.approverName = by.name();
        this.limitPaise = limitPaise;
        this.note = note;
        this.createdAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getExpenseId() {
        return expenseId;
    }

    ApprovalAction getAction() {
        return action;
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

    Instant getCreatedAt() {
        return createdAt;
    }
}
