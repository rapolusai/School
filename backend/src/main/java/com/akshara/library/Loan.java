package com.akshara.library;

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
 * A copy lent to a member: ISSUED until it comes back (RETURNED) or is reported LOST. Rows are never deleted; they
 * are the member's borrowing history.
 */
@Entity
@Table(schema = "library", name = "loan")
class Loan extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

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

    @Column(nullable = false, updatable = false)
    private LocalDate issuedOn;

    @Column(nullable = false)
    private LocalDate dueOn;

    @Column(nullable = false)
    private int renewals;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status;

    private LocalDate returnedOn;

    private Instant closedAt;

    @Column(updatable = false)
    private UUID issuedById;

    @Column(updatable = false)
    private String issuedByName;

    private UUID returnedById;

    private String returnedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Loan() {
    }

    Loan(Copy copy, MemberRef member, LocalDate issuedOn, LocalDate dueOn, Actor by) {
        this.id = Ids.newId();
        this.copyId = copy.getId();
        this.titleId = copy.getTitleId();
        this.memberType = member.type();
        this.studentId = member.studentId();
        this.staffUserId = member.staffUserId();
        this.issuedOn = issuedOn;
        this.dueOn = dueOn;
        this.status = LoanStatus.ISSUED;
        this.issuedById = by.id();
        this.issuedByName = by.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    boolean isOpen() {
        return status == LoanStatus.ISSUED;
    }

    void renew(LocalDate newDueOn) {
        this.dueOn = newDueOn;
        this.renewals++;
    }

    void returned(LocalDate on, Actor by) {
        close(LoanStatus.RETURNED, by);
        this.returnedOn = on;
    }

    void lost(Actor by) {
        close(LoanStatus.LOST, by);
    }

    private void close(LoanStatus to, Actor by) {
        this.status = to;
        this.closedAt = Instant.now();
        this.returnedById = by.id();
        this.returnedByName = by.name();
    }

    MemberRef member() {
        return MemberRef.of(memberType, studentId, staffUserId);
    }

    @Override
    public UUID getId() {
        return id;
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

    UUID getStudentId() {
        return studentId;
    }

    UUID getStaffUserId() {
        return staffUserId;
    }

    LocalDate getIssuedOn() {
        return issuedOn;
    }

    LocalDate getDueOn() {
        return dueOn;
    }

    int getRenewals() {
        return renewals;
    }

    LoanStatus getStatus() {
        return status;
    }

    LocalDate getReturnedOn() {
        return returnedOn;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    String getIssuedByName() {
        return issuedByName;
    }

    String getReturnedByName() {
        return returnedByName;
    }
}
