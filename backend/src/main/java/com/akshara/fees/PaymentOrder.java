package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * An online payment started for some of a student's instalments. Becomes PAID exactly once, whether the browser's
 * verification or the gateway's webhook arrives first; the late fee is fixed as of the day the order was made.
 */
@Entity
@Table(schema = "fees", name = "payment_order")
class PaymentOrder extends AssignedIdEntity {

    static final String CREATED = "CREATED";
    static final String PAID = "PAID";
    static final String FAILED = "FAILED";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private String gateway;

    @Column(nullable = false, updatable = false)
    private String gatewayOrderId;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "uuid[]", nullable = false, updatable = false)
    private List<UUID> instalmentIds = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDate lateFeeAsOf;

    @Column(nullable = false)
    private String status;

    private String gatewayPaymentId;

    private UUID receiptId;

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

    protected PaymentOrder() {
    }

    PaymentOrder(UUID id, UUID studentId, String gateway, String gatewayOrderId, long amountPaise,
            List<UUID> instalmentIds, LocalDate lateFeeAsOf, UUID createdBy, String createdByName) {
        this.id = id;
        this.studentId = studentId;
        this.gateway = gateway;
        this.gatewayOrderId = gatewayOrderId;
        this.amountPaise = amountPaise;
        this.currency = "INR";
        this.instalmentIds = new ArrayList<>(instalmentIds);
        this.lateFeeAsOf = lateFeeAsOf;
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

    void paid(String gatewayPaymentId, UUID receiptId) {
        this.status = PAID;
        this.gatewayPaymentId = gatewayPaymentId;
        this.receiptId = receiptId;
        this.failureReason = null;
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

    UUID getStudentId() {
        return studentId;
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

    List<UUID> getInstalmentIds() {
        return instalmentIds;
    }

    LocalDate getLateFeeAsOf() {
        return lateFeeAsOf;
    }

    String getStatus() {
        return status;
    }

    String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    UUID getReceiptId() {
        return receiptId;
    }

    String getFailureReason() {
        return failureReason;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
