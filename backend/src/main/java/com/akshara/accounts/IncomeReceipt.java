package com.akshara.accounts;

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

/** A receipt for income other than fees. Issued once, cancelled with a reason, never edited. */
@Entity
@Table(schema = "accounts", name = "income_receipt")
class IncomeReceipt extends AssignedIdEntity {

    record Fields(IncomeKind kind, UUID headId, LocalDate receivedOn, long amountPaise, String payerName,
            String payerPan, String payerAddress, PayMode mode, UUID bankAccountId, String reference,
            String eightyGNumber, String description) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String receiptNo;

    @Column(nullable = false, updatable = false)
    private String financialYear;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private IncomeKind kind;

    @Column(nullable = false, updatable = false)
    private UUID headId;

    @Column(nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private String payerName;

    @Column(updatable = false)
    private String payerPan;

    @Column(updatable = false)
    private String payerAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PayMode mode;

    @Column(updatable = false)
    private UUID bankAccountId;

    @Column(updatable = false)
    private String reference;

    @Column(name = "eighty_g_number", updatable = false)
    private String eightyGNumber;

    @Column(updatable = false)
    private String description;

    @Column(nullable = false)
    private String status = "ISSUED";

    private String cancelReason;

    private String cancelledByName;

    private Instant cancelledAt;

    @Column(updatable = false)
    private UUID receivedById;

    @Column(updatable = false)
    private String receivedByName;

    private LocalDate clearedOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected IncomeReceipt() {
    }

    IncomeReceipt(String receiptNo, String financialYear, int seq, Fields f, Actor by) {
        this.id = Ids.newId();
        this.receiptNo = receiptNo;
        this.financialYear = financialYear;
        this.seq = seq;
        this.kind = f.kind();
        this.headId = f.headId();
        this.receivedOn = f.receivedOn();
        this.amountPaise = f.amountPaise();
        this.payerName = f.payerName();
        this.payerPan = f.payerPan();
        this.payerAddress = f.payerAddress();
        this.mode = f.mode();
        this.bankAccountId = f.bankAccountId();
        this.reference = f.reference();
        this.eightyGNumber = f.eightyGNumber();
        this.description = f.description();
        this.receivedById = by.id();
        this.receivedByName = by.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void cancel(Actor by, String reason, Instant at) {
        this.status = "CANCELLED";
        this.cancelReason = reason;
        this.cancelledByName = by.name();
        this.cancelledAt = at;
    }

    void cleared(LocalDate on) {
        this.clearedOn = on;
    }

    boolean issued() {
        return "ISSUED".equals(status);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getReceiptNo() {
        return receiptNo;
    }

    String getFinancialYear() {
        return financialYear;
    }

    IncomeKind getKind() {
        return kind;
    }

    UUID getHeadId() {
        return headId;
    }

    LocalDate getReceivedOn() {
        return receivedOn;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    String getPayerName() {
        return payerName;
    }

    String getPayerPan() {
        return payerPan;
    }

    String getPayerAddress() {
        return payerAddress;
    }

    PayMode getMode() {
        return mode;
    }

    UUID getBankAccountId() {
        return bankAccountId;
    }

    String getReference() {
        return reference;
    }

    String getEightyGNumber() {
        return eightyGNumber;
    }

    String getDescription() {
        return description;
    }

    String getStatus() {
        return status;
    }

    String getCancelReason() {
        return cancelReason;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getReceivedByName() {
        return receivedByName;
    }

    LocalDate getClearedOn() {
        return clearedOn;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
