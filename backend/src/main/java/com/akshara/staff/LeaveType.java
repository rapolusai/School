package com.akshara.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A kind of leave the school gives (Casual, Sick, Earned, Maternity…) with its yearly allowance and how much of an
 * unused balance carries into the next academic year. Loss of pay has no allowance and is never short of balance.
 */
@Entity
@Table(schema = "staff", name = "leave_type")
class LeaveType extends AssignedIdEntity {

    record Fields(String name, String code, BigDecimal yearlyQuota, BigDecimal carryForwardCap,
            boolean halfDayAllowed, boolean lossOfPay, boolean active) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal yearlyQuota;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal carryForwardCap;

    @Column(nullable = false)
    private boolean halfDayAllowed;

    @Column(nullable = false)
    private boolean lossOfPay;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LeaveType() {
    }

    LeaveType(Fields fields) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        apply(fields);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Fields f) {
        this.name = f.name();
        this.code = f.code();
        this.yearlyQuota = f.yearlyQuota();
        this.carryForwardCap = f.carryForwardCap();
        this.halfDayAllowed = f.halfDayAllowed();
        this.lossOfPay = f.lossOfPay();
        this.active = f.active();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getCode() {
        return code;
    }

    BigDecimal getYearlyQuota() {
        return yearlyQuota;
    }

    BigDecimal getCarryForwardCap() {
        return carryForwardCap;
    }

    boolean isHalfDayAllowed() {
        return halfDayAllowed;
    }

    boolean isLossOfPay() {
        return lossOfPay;
    }

    boolean isActive() {
        return active;
    }
}
