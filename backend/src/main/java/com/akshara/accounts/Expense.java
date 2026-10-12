package com.akshara.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * An expense request. {@code amountPaise} is before GST, {@code totalPaise} includes it, {@code tdsPaise} is kept
 * back from the vendor and {@code paidPaise} is what leaves the bank or cash. Once PAID nothing changes.
 */
@Entity
@Table(schema = "accounts", name = "expense")
class Expense extends AssignedIdEntity {

    /** What the requester fills in. */
    record Fields(UUID headId, UUID costCentreId, UUID vendorId, String vendorName, LocalDate expenseDate,
            long amountPaise, long gstPaise, String tdsSection, BigDecimal tdsRatePercent, long tdsPaise,
            String description, String billNumber) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private String number;

    @Column(nullable = false)
    private String financialYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status;

    @Column(nullable = false)
    private UUID headId;

    private UUID costCentreId;

    private UUID vendorId;

    private String vendorName;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private long gstPaise;

    @Column(nullable = false)
    private long totalPaise;

    private String tdsSection;

    private BigDecimal tdsRatePercent;

    @Column(nullable = false)
    private long tdsPaise;

    @Column(nullable = false)
    private long paidPaise;

    @Column(nullable = false)
    private String description;

    private String billNumber;

    @Column(updatable = false)
    private UUID recurringId;

    @Column(updatable = false)
    private LocalDate recurringMonth;

    @Column(nullable = false)
    private boolean budgetWarning;

    private Long escalatedAbovePaise;

    @Column(updatable = false)
    private UUID requestedById;

    @Column(updatable = false)
    private String requestedByName;

    private Instant submittedAt;

    private UUID approvedById;

    private String approvedByName;

    private Instant approvedAt;

    private UUID rejectedById;

    private String rejectedByName;

    private Instant rejectedAt;

    private String rejectReason;

    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    private PayMode paymentMode;

    private UUID bankAccountId;

    private String paymentReference;

    private UUID paidById;

    private String paidByName;

    private Instant paidAt;

    private LocalDate clearedOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Expense() {
    }

    Expense(Fields f, Actor by, UUID recurringId, LocalDate recurringMonth) {
        this.id = Ids.newId();
        this.status = ExpenseStatus.DRAFT;
        this.requestedById = by == null ? null : by.id();
        this.requestedByName = by == null ? null : by.name();
        this.recurringId = recurringId;
        this.recurringMonth = recurringMonth;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(f);
    }

    void update(Fields f) {
        this.headId = f.headId();
        this.costCentreId = f.costCentreId();
        this.vendorId = f.vendorId();
        this.vendorName = f.vendorName();
        this.expenseDate = f.expenseDate();
        this.financialYear = FinancialYear.of(f.expenseDate()).label();
        this.amountPaise = f.amountPaise();
        this.gstPaise = f.gstPaise();
        this.totalPaise = f.amountPaise() + f.gstPaise();
        this.tdsSection = f.tdsSection();
        this.tdsRatePercent = f.tdsRatePercent();
        this.tdsPaise = f.tdsPaise();
        this.paidPaise = totalPaise - f.tdsPaise();
        this.description = f.description();
        this.billNumber = f.billNumber();
    }

    void submitted(String number, Instant at, boolean budgetWarning) {
        this.status = ExpenseStatus.SUBMITTED;
        if (this.number == null) {
            this.number = number;
        }
        this.submittedAt = at;
        this.budgetWarning = budgetWarning;
        this.escalatedAbovePaise = null;
        this.rejectedById = null;
        this.rejectedByName = null;
        this.rejectedAt = null;
        this.rejectReason = null;
    }

    void approved(Actor by, Instant at, boolean budgetWarning) {
        this.status = ExpenseStatus.APPROVED;
        this.approvedById = by.id();
        this.approvedByName = by.name();
        this.approvedAt = at;
        this.budgetWarning = budgetWarning;
    }

    void escalated(long abovePaise) {
        this.escalatedAbovePaise = abovePaise;
    }

    void rejected(Actor by, Instant at, String reason) {
        this.status = ExpenseStatus.REJECTED;
        this.rejectedById = by.id();
        this.rejectedByName = by.name();
        this.rejectedAt = at;
        this.rejectReason = reason;
    }

    void reopened() {
        this.status = ExpenseStatus.DRAFT;
        this.escalatedAbovePaise = null;
    }

    void paid(LocalDate on, PayMode mode, UUID bankAccountId, String reference, Actor by, Instant at) {
        this.status = ExpenseStatus.PAID;
        this.paidOn = on;
        this.paymentMode = mode;
        this.bankAccountId = bankAccountId;
        this.paymentReference = reference;
        this.paidById = by.id();
        this.paidByName = by.name();
        this.paidAt = at;
    }

    void cleared(LocalDate on) {
        this.clearedOn = on;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getNumber() {
        return number;
    }

    String getFinancialYear() {
        return financialYear;
    }

    ExpenseStatus getStatus() {
        return status;
    }

    UUID getHeadId() {
        return headId;
    }

    UUID getCostCentreId() {
        return costCentreId;
    }

    UUID getVendorId() {
        return vendorId;
    }

    String getVendorName() {
        return vendorName;
    }

    LocalDate getExpenseDate() {
        return expenseDate;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    long getGstPaise() {
        return gstPaise;
    }

    long getTotalPaise() {
        return totalPaise;
    }

    String getTdsSection() {
        return tdsSection;
    }

    BigDecimal getTdsRatePercent() {
        return tdsRatePercent;
    }

    long getTdsPaise() {
        return tdsPaise;
    }

    long getPaidPaise() {
        return paidPaise;
    }

    String getDescription() {
        return description;
    }

    String getBillNumber() {
        return billNumber;
    }

    UUID getRecurringId() {
        return recurringId;
    }

    LocalDate getRecurringMonth() {
        return recurringMonth;
    }

    boolean isBudgetWarning() {
        return budgetWarning;
    }

    Long getEscalatedAbovePaise() {
        return escalatedAbovePaise;
    }

    UUID getRequestedById() {
        return requestedById;
    }

    String getRequestedByName() {
        return requestedByName;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    String getApprovedByName() {
        return approvedByName;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }

    String getRejectedByName() {
        return rejectedByName;
    }

    Instant getRejectedAt() {
        return rejectedAt;
    }

    String getRejectReason() {
        return rejectReason;
    }

    LocalDate getPaidOn() {
        return paidOn;
    }

    PayMode getPaymentMode() {
        return paymentMode;
    }

    UUID getBankAccountId() {
        return bankAccountId;
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

    LocalDate getClearedOn() {
        return clearedOn;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getVersion() {
        return version;
    }
}
