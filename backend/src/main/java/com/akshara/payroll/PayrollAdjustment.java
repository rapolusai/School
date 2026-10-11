package com.akshara.payroll;

import java.math.BigDecimal;
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

/**
 * A manual change to one person's pay in a draft run, with its reason: arrears, a bonus, a deduction (amounts in
 * paise) or a loss-of-pay correction (days, positive or negative). Added or removed, never edited.
 */
@Entity
@Table(schema = "payroll", name = "adjustment")
class PayrollAdjustment extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AdjustmentKind kind;

    @Column(updatable = false)
    private Long amountPaise;

    @Column(updatable = false)
    private BigDecimal days;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PayrollAdjustment() {
    }

    PayrollAdjustment(UUID runId, UUID userId, AdjustmentKind kind, Long amountPaise, BigDecimal days, String reason,
            Actor by) {
        this.id = Ids.newId();
        this.runId = runId;
        this.userId = userId;
        this.kind = kind;
        this.amountPaise = amountPaise;
        this.days = days;
        this.reason = reason;
        this.createdById = by == null ? null : by.id();
        this.createdByName = by == null ? null : by.name();
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getUserId() {
        return userId;
    }

    AdjustmentKind getKind() {
        return kind;
    }

    Long getAmountPaise() {
        return amountPaise;
    }

    BigDecimal getDays() {
        return days;
    }

    String getReason() {
        return reason;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
