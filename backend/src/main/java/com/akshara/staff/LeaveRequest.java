package com.akshara.staff;

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
 * One application for leave: a date range in one academic year (or half of one day), the working days it costs, and
 * who decided it. {@code approverUserId} is the department head it went to; null means the School Admin and Principal.
 * Names of the people who decided or cancelled are kept so the history still reads correctly later.
 */
@Entity
@Table(schema = "staff", name = "leave_request")
class LeaveRequest extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID leaveTypeId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(nullable = false, updatable = false)
    private LocalDate toDate;

    @Column(nullable = false, updatable = false)
    private boolean halfDay;

    @Column(nullable = false, updatable = false, precision = 5, scale = 1)
    private BigDecimal days;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status;

    @Column(updatable = false)
    private UUID approverUserId;

    private UUID decidedById;

    private String decidedByName;

    private Instant decidedAt;

    private String decisionComment;

    private UUID cancelledById;

    private String cancelledByName;

    private Instant cancelledAt;

    private String cancelComment;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LeaveRequest() {
    }

    LeaveRequest(UUID userId, UUID leaveTypeId, UUID academicYearId, LocalDate fromDate, LocalDate toDate,
            boolean halfDay, BigDecimal days, String reason, UUID approverUserId, Instant at) {
        this.id = Ids.newId();
        this.userId = userId;
        this.leaveTypeId = leaveTypeId;
        this.academicYearId = academicYearId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.halfDay = halfDay;
        this.days = days;
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
        this.approverUserId = approverUserId;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void decide(LeaveStatus outcome, Actor by, String comment, Instant at) {
        this.status = outcome;
        this.decidedById = by.id();
        this.decidedByName = by.name();
        this.decidedAt = at;
        this.decisionComment = comment;
    }

    void cancel(Actor by, String comment, Instant at) {
        this.status = LeaveStatus.CANCELLED;
        this.cancelledById = by.id();
        this.cancelledByName = by.name();
        this.cancelledAt = at;
        this.cancelComment = comment;
    }

    boolean overlaps(LocalDate from, LocalDate to) {
        return !from.isAfter(toDate) && !to.isBefore(fromDate);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getLeaveTypeId() {
        return leaveTypeId;
    }

    UUID getAcademicYearId() {
        return academicYearId;
    }

    LocalDate getFromDate() {
        return fromDate;
    }

    LocalDate getToDate() {
        return toDate;
    }

    boolean isHalfDay() {
        return halfDay;
    }

    BigDecimal getDays() {
        return days;
    }

    String getReason() {
        return reason;
    }

    LeaveStatus getStatus() {
        return status;
    }

    UUID getApproverUserId() {
        return approverUserId;
    }

    String getDecidedByName() {
        return decidedByName;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    String getDecisionComment() {
        return decisionComment;
    }

    String getCancelledByName() {
        return cancelledByName;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    String getCancelComment() {
        return cancelComment;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
