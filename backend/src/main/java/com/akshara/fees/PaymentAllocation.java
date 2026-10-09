package com.akshara.fees;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One line of the money ledger: part of a receipt allocated to a due, to an instalment's late fee, or kept as an
 * advance; or the reversal of such a line when its receipt is cancelled. Never changed or deleted.
 */
@Entity
@Immutable
@Table(schema = "fees", name = "payment_allocation")
class PaymentAllocation extends AssignedIdEntity {

    static final String ALLOCATION = "ALLOCATION";
    static final String REVERSAL = "REVERSAL";
    static final String DUE = "DUE";
    static final String LATE_FEE = "LATE_FEE";
    static final String ADVANCE = "ADVANCE";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID receiptId;

    @Column(nullable = false)
    private UUID studentId;

    /** The line's place on its receipt; a reversal has the number of the line it reverses. */
    @Column(nullable = false)
    private int lineNo;

    @Column(nullable = false)
    private String entry;

    @Column(nullable = false)
    private String kind;

    private UUID dueId;

    private UUID instalmentId;

    private UUID headId;

    private String headName;

    private String instalmentLabel;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private Instant createdAt;

    protected PaymentAllocation() {
    }

    PaymentAllocation(UUID receiptId, UUID studentId, int lineNo, String entry, String kind, UUID dueId,
            UUID instalmentId, UUID headId, String headName, String instalmentLabel, long amountPaise) {
        this.id = Ids.newId();
        this.receiptId = receiptId;
        this.studentId = studentId;
        this.lineNo = lineNo;
        this.entry = entry;
        this.kind = kind;
        this.dueId = dueId;
        this.instalmentId = instalmentId;
        this.headId = headId;
        this.headName = headName;
        this.instalmentLabel = instalmentLabel;
        this.amountPaise = amountPaise;
        this.createdAt = Instant.now();
    }

    /** The reversal of this allocation, for a cancelled receipt. */
    PaymentAllocation reversal() {
        return new PaymentAllocation(receiptId, studentId, lineNo, REVERSAL, kind, dueId, instalmentId, headId,
                headName, instalmentLabel, -amountPaise);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getReceiptId() {
        return receiptId;
    }

    UUID getStudentId() {
        return studentId;
    }

    int getLineNo() {
        return lineNo;
    }

    String getEntry() {
        return entry;
    }

    String getKind() {
        return kind;
    }

    UUID getDueId() {
        return dueId;
    }

    UUID getInstalmentId() {
        return instalmentId;
    }

    UUID getHeadId() {
        return headId;
    }

    String getHeadName() {
        return headName;
    }

    String getInstalmentLabel() {
        return instalmentLabel;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
