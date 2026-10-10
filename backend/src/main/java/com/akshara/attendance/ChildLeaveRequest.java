package com.akshara.attendance;

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
 * A parent's note that their child will be (or was) away: a date range (or half of one day) and the reason. The class
 * teacher of {@code sectionId} decides it. Names of the people who applied, decided or cancelled are kept so the
 * history still reads correctly later.
 */
@Entity
@Table(schema = "attendance", name = "leave_request")
class ChildLeaveRequest extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID sectionId;

    @Column(nullable = false, updatable = false)
    private UUID academicYearId;

    @Column(nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(nullable = false, updatable = false)
    private LocalDate toDate;

    @Column(nullable = false, updatable = false)
    private boolean halfDay;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChildLeaveStatus status;

    @Column(updatable = false)
    private UUID requestedById;

    @Column(updatable = false)
    private String requestedByName;

    private UUID decidedById;

    private String decidedByName;

    private Instant decidedAt;

    private String decisionComment;

    private UUID cancelledById;

    private String cancelledByName;

    private Instant cancelledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ChildLeaveRequest() {
    }

    ChildLeaveRequest(UUID studentId, UUID sectionId, UUID academicYearId, LocalDate fromDate, LocalDate toDate,
            boolean halfDay, String reason, Actor requestedBy, Instant at) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.sectionId = sectionId;
        this.academicYearId = academicYearId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.halfDay = halfDay;
        this.reason = reason;
        this.status = ChildLeaveStatus.PENDING;
        this.requestedById = requestedBy.id();
        this.requestedByName = requestedBy.name();
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void decide(ChildLeaveStatus outcome, Actor by, String comment, Instant at) {
        this.status = outcome;
        this.decidedById = by.id();
        this.decidedByName = by.name();
        this.decidedAt = at;
        this.decisionComment = comment;
    }

    void cancel(Actor by, Instant at) {
        this.status = ChildLeaveStatus.CANCELLED;
        this.cancelledById = by.id();
        this.cancelledByName = by.name();
        this.cancelledAt = at;
    }

    boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getSectionId() {
        return sectionId;
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

    String getReason() {
        return reason;
    }

    ChildLeaveStatus getStatus() {
        return status;
    }

    UUID getRequestedById() {
        return requestedById;
    }

    String getRequestedByName() {
        return requestedByName;
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

    Instant getCreatedAt() {
        return createdAt;
    }
}
