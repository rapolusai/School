package com.akshara.library;

import java.time.Instant;
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
 * What a member owes the library for a loan: an overdue fine worked out on return, or the charge for a lost or
 * damaged copy. DUE until it is paid at the library desk (with a receipt number) or waived with a reason. Kept in the
 * library, apart from school fees. Never deleted.
 */
@Entity
@Table(schema = "library", name = "fine")
class Fine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID loanId;

    @Column(nullable = false, updatable = false)
    private UUID copyId;

    @Column(nullable = false, updatable = false)
    private UUID titleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MemberType memberType;

    @Column(updatable = false)
    private UUID studentId;

    @Column(updatable = false)
    private UUID staffUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FineKind kind;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(updatable = false)
    private Integer daysLate;

    @Column(updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FineStatus status;

    private String receiptNo;

    @Enumerated(EnumType.STRING)
    private LibraryPaymentMode paymentMode;

    private String paymentReference;

    private Instant paidAt;

    private UUID collectedById;

    private String collectedByName;

    private String waiveReason;

    private Instant waivedAt;

    private UUID waivedById;

    private String waivedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Fine() {
    }

    Fine(Loan loan, FineKind kind, long amountPaise, Integer daysLate, String note) {
        this.id = Ids.newId();
        this.loanId = loan.getId();
        this.copyId = loan.getCopyId();
        this.titleId = loan.getTitleId();
        this.memberType = loan.getMemberType();
        this.studentId = loan.getStudentId();
        this.staffUserId = loan.getStaffUserId();
        this.kind = kind;
        this.amountPaise = amountPaise;
        this.daysLate = daysLate;
        this.note = note;
        this.status = FineStatus.DUE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    boolean isDue() {
        return status == FineStatus.DUE;
    }

    void paid(String receiptNo, LibraryPaymentMode mode, String reference, Instant at, Actor by) {
        this.status = FineStatus.PAID;
        this.receiptNo = receiptNo;
        this.paymentMode = mode;
        this.paymentReference = reference;
        this.paidAt = at;
        this.collectedById = by.id();
        this.collectedByName = by.name();
    }

    void waived(String reason, Instant at, Actor by) {
        this.status = FineStatus.WAIVED;
        this.waiveReason = reason;
        this.waivedAt = at;
        this.waivedById = by.id();
        this.waivedByName = by.name();
    }

    MemberRef member() {
        return MemberRef.of(memberType, studentId, staffUserId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getLoanId() {
        return loanId;
    }

    UUID getCopyId() {
        return copyId;
    }

    UUID getTitleId() {
        return titleId;
    }

    MemberType getMemberType() {
        return memberType;
    }

    FineKind getKind() {
        return kind;
    }

    long getAmountPaise() {
        return amountPaise;
    }

    Integer getDaysLate() {
        return daysLate;
    }

    String getNote() {
        return note;
    }

    FineStatus getStatus() {
        return status;
    }

    String getReceiptNo() {
        return receiptNo;
    }

    LibraryPaymentMode getPaymentMode() {
        return paymentMode;
    }

    String getPaymentReference() {
        return paymentReference;
    }

    Instant getPaidAt() {
        return paidAt;
    }

    String getCollectedByName() {
        return collectedByName;
    }

    String getWaiveReason() {
        return waiveReason;
    }

    Instant getWaivedAt() {
        return waivedAt;
    }

    String getWaivedByName() {
        return waivedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
