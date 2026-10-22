package com.akshara.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A month's payroll cost as approved: gross pay plus the employer's contributions (from PayrollApproved). */
@Entity
@Table(schema = "accounts", name = "payroll_cost")
class PayrollCost extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private int staffCount;

    @Column(nullable = false, updatable = false)
    private long grossPaise;

    @Column(nullable = false, updatable = false)
    private long deductionsPaise;

    @Column(nullable = false, updatable = false)
    private long netPaise;

    @Column(nullable = false, updatable = false)
    private long employerPaise;

    @Column(nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PayrollCost() {
    }

    PayrollCost(LocalDate month, UUID runId, int staffCount, long grossPaise, long deductionsPaise, long netPaise,
            long employerPaise, Instant approvedAt) {
        this.id = Ids.newId();
        this.month = month;
        this.runId = runId;
        this.staffCount = staffCount;
        this.grossPaise = grossPaise;
        this.deductionsPaise = deductionsPaise;
        this.netPaise = netPaise;
        this.employerPaise = employerPaise;
        this.approvedAt = approvedAt;
        this.createdAt = Instant.now();
    }

    /** What the month cost the school: gross pay plus the employer's PF, ESI and charges. */
    long costPaise() {
        return grossPaise + employerPaise;
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getMonth() {
        return month;
    }

    UUID getRunId() {
        return runId;
    }

    int getStaffCount() {
        return staffCount;
    }

    long getGrossPaise() {
        return grossPaise;
    }

    long getDeductionsPaise() {
        return deductionsPaise;
    }

    long getNetPaise() {
        return netPaise;
    }

    long getEmployerPaise() {
        return employerPaise;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }
}
