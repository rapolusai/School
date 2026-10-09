package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A fee receipt. Immutable once issued: the database lets the API change only the cancellation columns (and the
 * runtime role cannot delete it), so the class only ever updates those. Student details are copied in so a reprint
 * shows exactly what was issued.
 */
@Entity
@Table(schema = "fees", name = "receipt")
@DynamicUpdate
class Receipt extends AssignedIdEntity {

    static final String ISSUED = "ISSUED";
    static final String CANCELLED = "CANCELLED";
    static final String COUNTER = "COUNTER";
    static final String ONLINE = "ONLINE";

    /** Everything printed on a receipt, fixed at issue. */
    record Issue(String receiptNo, String financialYear, int seq, UUID studentId, String studentName,
            String admissionNo, String classLabel, LocalDate receivedOn, Instant receivedAt, PaymentMode mode,
            String chequeNo, String bankName, String reference, long amountPaise, long lateFeePaise, String source,
            String gateway, String gatewayPaymentId, String remarks, UUID collectedBy, String collectedByName) {
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

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private String studentName;

    @Column(nullable = false, updatable = false)
    private String admissionNo;

    @Column(updatable = false)
    private String classLabel;

    @Column(nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMode mode;

    @Column(updatable = false)
    private String chequeNo;

    @Column(updatable = false)
    private String bankName;

    @Column(updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private long lateFeePaise;

    @Column(nullable = false, updatable = false)
    private String source;

    @Column(updatable = false)
    private String gateway;

    @Column(updatable = false)
    private String gatewayPaymentId;

    @Column(updatable = false)
    private String remarks;

    @Column(updatable = false)
    private UUID collectedBy;

    @Column(nullable = false, updatable = false)
    private String collectedByName;

    @Column(nullable = false)
    private String status;

    private Instant cancelledAt;

    private UUID cancelledBy;

    private String cancelledByName;

    private String cancelReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Receipt() {
    }

    Receipt(Issue issue) {
        this.id = Ids.newId();
        this.receiptNo = issue.receiptNo();
        this.financialYear = issue.financialYear();
        this.seq = issue.seq();
        this.studentId = issue.studentId();
        this.studentName = issue.studentName();
        this.admissionNo = issue.admissionNo();
        this.classLabel = issue.classLabel();
        this.receivedOn = issue.receivedOn();
        this.receivedAt = issue.receivedAt();
        this.mode = issue.mode();
        this.chequeNo = issue.chequeNo();
        this.bankName = issue.bankName();
        this.reference = issue.reference();
        this.amountPaise = issue.amountPaise();
        this.lateFeePaise = issue.lateFeePaise();
        this.source = issue.source();
        this.gateway = issue.gateway();
        this.gatewayPaymentId = issue.gatewayPaymentId();
        this.remarks = issue.remarks();
        this.collectedBy = issue.collectedBy();
        this.collectedByName = issue.collectedByName();
        this.status = ISSUED;
        this.createdAt = Instant.now();
    }

    void cancel(UUID by, String byName, String reason) {
        this.status = CANCELLED;
        this.cancelledAt = Instant.now();
        this.cancelledBy = by;
        this.cancelledByName = byName;
        this.cancelReason = reason;
    }

    boolean isCancelled() {
        return CANCELLED.equals(status);
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

    int getSeq() {
        return seq;
    }

    UUID getStudentId() {
        return studentId;
    }

    String getStudentName() {
        return studentName;
    }

    String getAdmissionNo() {
        return admissionNo;
    }

    String getClassLabel() {
        return classLabel;
    }

    LocalDate getReceivedOn() {
        return receivedOn;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }

    PaymentMode getMode() {
        return mode;
    }

    String getChequeNo() {
        return chequeNo;
    }

    String getBankName() {
        return bankName;
    }

    String getReference() {
        return reference;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    long getLateFeePaise() {
        return lateFeePaise;
    }

    String getSource() {
        return source;
    }

    String getGateway() {
        return gateway;
    }

    String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    String getRemarks() {
        return remarks;
    }

    String getCollectedByName() {
        return collectedByName;
    }

    String getStatus() {
        return status;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    String getCancelReason() {
        return cancelReason;
    }
}
