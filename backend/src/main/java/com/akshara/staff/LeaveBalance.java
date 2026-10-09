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
 * A balance the school set by hand for one person, leave type and academic year (for example the earned leave a
 * teacher brought from before the school used Akshara). Without one, the balance is worked out (see LeaveBalances).
 */
@Entity
@Table(schema = "staff", name = "leave_balance")
class LeaveBalance extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID leaveTypeId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal opening;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal accrued;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LeaveBalance() {
    }

    LeaveBalance(UUID userId, UUID leaveTypeId, UUID academicYearId, BigDecimal opening, BigDecimal accrued) {
        this.id = Ids.newId();
        this.userId = userId;
        this.leaveTypeId = leaveTypeId;
        this.academicYearId = academicYearId;
        this.opening = opening;
        this.accrued = accrued;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void set(BigDecimal opening, BigDecimal accrued) {
        this.opening = opening;
        this.accrued = accrued;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getLeaveTypeId() {
        return leaveTypeId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    BigDecimal getOpening() {
        return opening;
    }

    BigDecimal getAccrued() {
        return accrued;
    }
}
