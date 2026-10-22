package com.akshara.hostel;

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

/** An outing (day pass) or home leave of a boarder (see {@link HostelPassStatus}). Rows are never deleted. */
@Entity
@Table(schema = "hostel", name = "pass")
class HostelPass extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID hostelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PassKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private HostelPassSource source;

    @Column(updatable = false)
    private UUID requestedByUserId;

    @Column(updatable = false)
    private String requestedByName;

    @Column(nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(nullable = false, updatable = false)
    private LocalDate toDate;

    @Column(nullable = false, updatable = false)
    private Instant returnBy;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HostelPassStatus status;

    private String decidedByName;

    private Instant decidedAt;

    private String decisionNote;

    private Instant outAt;

    private String outByName;

    private Instant inAt;

    private String inByName;

    @Column(nullable = false)
    private boolean late;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected HostelPass() {
    }

    HostelPass(UUID studentId, UUID hostelId, PassKind kind, HostelPassSource source, Actor requestedBy,
            LocalDate fromDate, LocalDate toDate, Instant returnBy, String reason, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.hostelId = hostelId;
        this.kind = kind;
        this.source = source;
        this.requestedByUserId = requestedBy.id();
        this.requestedByName = requestedBy.name();
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.returnBy = returnBy;
        this.reason = reason;
        this.status = HostelPassStatus.REQUESTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void approve(String note, Actor by, Instant now) {
        this.status = HostelPassStatus.APPROVED;
        this.decidedByName = by.name();
        this.decidedAt = now;
        this.decisionNote = note;
    }

    void reject(String note, Actor by, Instant now) {
        this.status = HostelPassStatus.REJECTED;
        this.decidedByName = by.name();
        this.decidedAt = now;
        this.decisionNote = note;
    }

    void cancel() {
        this.status = HostelPassStatus.CANCELLED;
    }

    void markOut(Actor by, Instant now) {
        this.status = HostelPassStatus.OUT;
        this.outAt = now;
        this.outByName = by.name();
    }

    /** Marks the boarder back; returns true when they are late. */
    boolean markIn(Actor by, Instant now) {
        this.status = HostelPassStatus.RETURNED;
        this.inAt = now;
        this.inByName = by.name();
        this.late = HostelRules.isLate(returnBy, now);
        return late;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getHostelId() {
        return hostelId;
    }

    PassKind getKind() {
        return kind;
    }

    HostelPassSource getSource() {
        return source;
    }

    UUID getRequestedByUserId() {
        return requestedByUserId;
    }

    String getRequestedByName() {
        return requestedByName;
    }

    LocalDate getFromDate() {
        return fromDate;
    }

    LocalDate getToDate() {
        return toDate;
    }

    Instant getReturnBy() {
        return returnBy;
    }

    String getReason() {
        return reason;
    }

    HostelPassStatus getStatus() {
        return status;
    }

    String getDecidedByName() {
        return decidedByName;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    String getDecisionNote() {
        return decisionNote;
    }

    Instant getOutAt() {
        return outAt;
    }

    String getOutByName() {
        return outByName;
    }

    Instant getInAt() {
        return inAt;
    }

    String getInByName() {
        return inByName;
    }

    boolean isLate() {
        return late;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
