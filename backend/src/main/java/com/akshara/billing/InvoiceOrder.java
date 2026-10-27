package com.akshara.billing;

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
 * An online payment a School Admin started for the whole balance of one subscription invoice, at Akshara's own payment
 * gateway. Becomes PAID exactly once, whether the browser's verification or the gateway's webhook arrives first.
 */
@Entity
@Table(schema = "billing", name = "payment_order")
class InvoiceOrder extends AssignedIdEntity {

    static final String CREATED = "CREATED";
    static final String PAID = "PAID";
    static final String FAILED = "FAILED";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false, updatable = false)
    private String gateway;

    @Column(nullable = false, updatable = false)
    private String gatewayOrderId;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false)
    private String status;

    private String gatewayPaymentId;

    private UUID invoicePaymentId;

    private String failureReason;

    @Column(updatable = false)
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected InvoiceOrder() {
    }

    InvoiceOrder(UUID id, UUID invoiceId, String gateway, String gatewayOrderId, long amountPaise, UUID createdBy,
            String createdByName) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.gateway = gateway;
        this.gatewayOrderId = gatewayOrderId;
        this.amountPaise = amountPaise;
        this.currency = "INR";
        this.status = CREATED;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    static UUID newId() {
        return Ids.newId();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Paid, and recorded on the invoice as {@code invoicePaymentId}. */
    void paid(String gatewayPaymentId, UUID invoicePaymentId) {
        this.status = PAID;
        this.gatewayPaymentId = gatewayPaymentId;
        this.invoicePaymentId = invoicePaymentId;
        this.failureReason = null;
    }

    /** Paid at the gateway, but the invoice was no longer due: the money is to be refunded there. */
    void paidNotApplied(String gatewayPaymentId, String reason) {
        this.status = PAID;
        this.gatewayPaymentId = gatewayPaymentId;
        this.invoicePaymentId = null;
        this.failureReason = reason;
    }

    void failed(String reason) {
        this.status = FAILED;
        this.failureReason = reason;
    }

    boolean isPaid() {
        return PAID.equals(status);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    String getGateway() {
        return gateway;
    }

    String getGatewayOrderId() {
        return gatewayOrderId;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    String getCurrency() {
        return currency;
    }

    String getStatus() {
        return status;
    }

    String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    UUID getInvoicePaymentId() {
        return invoicePaymentId;
    }

    String getFailureReason() {
        return failureReason;
    }

    UUID getCreatedBy() {
        return createdBy;
    }
}
