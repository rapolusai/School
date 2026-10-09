package com.akshara.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A payment the Super Admin recorded against an invoice (from the bank statement). Never changed or deleted. */
@Entity
@Immutable
@Table(schema = "billing", name = "invoice_payment")
class InvoicePayment extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMode mode;

    @Column(nullable = false)
    private String reference;

    @Column(nullable = false)
    private LocalDate paidOn;

    private UUID recordedById;

    @Column(nullable = false)
    private String recordedByName;

    @Column(nullable = false)
    private Instant createdAt;

    protected InvoicePayment() {
    }

    InvoicePayment(UUID invoiceId, long amountPaise, PaymentMode mode, String reference, LocalDate paidOn,
            UUID recordedById, String recordedByName) {
        this.id = Ids.newId();
        this.invoiceId = invoiceId;
        this.amountPaise = amountPaise;
        this.mode = mode;
        this.reference = reference;
        this.paidOn = paidOn;
        this.recordedById = recordedById;
        this.recordedByName = recordedByName;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    PaymentMode getMode() {
        return mode;
    }

    String getReference() {
        return reference;
    }

    LocalDate getPaidOn() {
        return paidOn;
    }

    String getRecordedByName() {
        return recordedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
