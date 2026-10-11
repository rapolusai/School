package com.akshara.payroll;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One month's payroll for the school. While DRAFT it is recalculated freely; approving locks it (APPROVED), and it is
 * then marked PAID with the payment date. A locked run never changes: a correction is an adjustment in a later run.
 * The totals are the sums of its payslips, kept here for lists and the PayrollApproved event.
 */
@Entity
@Table(schema = "payroll", name = "payroll_run")
class PayrollRun extends AssignedIdEntity {

    /** Sums over the run's payslips. */
    record Totals(int staffCount, long gross, long deductions, long net, long employer, long pfEmployee,
            long pfEmployer, long pfAdmin, long edli, long esiEmployee, long esiEmployer, long pt, long tds) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProrationBasis prorationBasis;

    @Column(nullable = false)
    private int daysInMonth;

    @Column(nullable = false)
    private int workingDays;

    @Column(nullable = false)
    private int staffCount;

    @Column(nullable = false)
    private long grossPaise;

    @Column(nullable = false)
    private long deductionsPaise;

    @Column(nullable = false)
    private long netPaise;

    @Column(nullable = false)
    private long employerPaise;

    @Column(nullable = false)
    private long pfEmployeePaise;

    @Column(nullable = false)
    private long pfEmployerPaise;

    @Column(nullable = false)
    private long pfAdminPaise;

    @Column(nullable = false)
    private long edliPaise;

    @Column(nullable = false)
    private long esiEmployeePaise;

    @Column(nullable = false)
    private long esiEmployerPaise;

    @Column(nullable = false)
    private long ptPaise;

    @Column(nullable = false)
    private long tdsPaise;

    @Column(nullable = false)
    private Instant calculatedAt;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    private UUID approvedById;

    private String approvedByName;

    private Instant approvedAt;

    private LocalDate paidOn;

    private String paymentReference;

    private UUID paidById;

    private String paidByName;

    private Instant paidAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PayrollRun() {
    }

    PayrollRun(YearMonth month, ProrationBasis basis, int workingDays, Actor by) {
        this.id = Ids.newId();
        this.month = month.atDay(1);
        this.status = RunStatus.DRAFT;
        this.prorationBasis = basis;
        this.daysInMonth = month.lengthOfMonth();
        this.workingDays = workingDays;
        this.createdById = by == null ? null : by.id();
        this.createdByName = by == null ? null : by.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.calculatedAt = createdAt;
    }

    /** A recalculation: the basis in force now, the month's working days and the new totals. */
    void calculated(ProrationBasis basis, int workingDays, Totals t) {
        this.prorationBasis = basis;
        this.workingDays = workingDays;
        this.staffCount = t.staffCount();
        this.grossPaise = t.gross();
        this.deductionsPaise = t.deductions();
        this.netPaise = t.net();
        this.employerPaise = t.employer();
        this.pfEmployeePaise = t.pfEmployee();
        this.pfEmployerPaise = t.pfEmployer();
        this.pfAdminPaise = t.pfAdmin();
        this.edliPaise = t.edli();
        this.esiEmployeePaise = t.esiEmployee();
        this.esiEmployerPaise = t.esiEmployer();
        this.ptPaise = t.pt();
        this.tdsPaise = t.tds();
        this.calculatedAt = Instant.now();
    }

    void approve(Actor by, Instant at) {
        this.status = RunStatus.APPROVED;
        this.approvedById = by.id();
        this.approvedByName = by.name();
        this.approvedAt = at;
    }

    void paid(LocalDate on, String reference, Actor by, Instant at) {
        this.status = RunStatus.PAID;
        this.paidOn = on;
        this.paymentReference = reference;
        this.paidById = by.id();
        this.paidByName = by.name();
        this.paidAt = at;
    }

    Totals totals() {
        return new Totals(staffCount, grossPaise, deductionsPaise, netPaise, employerPaise, pfEmployeePaise,
                pfEmployerPaise, pfAdminPaise, edliPaise, esiEmployeePaise, esiEmployerPaise, ptPaise, tdsPaise);
    }

    YearMonth yearMonth() {
        return YearMonth.from(month);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    LocalDate getMonth() {
        return month;
    }

    RunStatus getStatus() {
        return status;
    }

    ProrationBasis getProrationBasis() {
        return prorationBasis;
    }

    int getDaysInMonth() {
        return daysInMonth;
    }

    int getWorkingDays() {
        return workingDays;
    }

    Instant getCalculatedAt() {
        return calculatedAt;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getApprovedByName() {
        return approvedByName;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }

    LocalDate getPaidOn() {
        return paidOn;
    }

    String getPaymentReference() {
        return paymentReference;
    }

    String getPaidByName() {
        return paidByName;
    }

    Instant getPaidAt() {
        return paidAt;
    }

    long getVersion() {
        return version;
    }
}
