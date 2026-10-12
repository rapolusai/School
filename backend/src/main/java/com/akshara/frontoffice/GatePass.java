package com.akshara.frontoffice;

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
 * An early pickup pass for a student (see {@link PassStatus}). The OTP sent to the primary guardian is kept only as a
 * salted hash; the gate's wrong tries are counted and the fifth locks the pass. Rows are never deleted.
 */
@Entity
@Table(schema = "frontoffice", name = "gate_pass")
class GatePass extends AssignedIdEntity {

    /** Wrong OTPs at the gate before the pass locks. */
    static final int MAX_WRONG_ATTEMPTS = 5;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PassSource source;

    @Column(updatable = false)
    private UUID requestedByUserId;

    @Column(updatable = false)
    private String requestedByName;

    @Column(nullable = false)
    private Instant pickupAt;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollectorKind collectorKind;

    private String collectorName;

    private String collectorPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PassStatus status;

    private String otpHash;

    private Instant otpExpiresAt;

    @Column(nullable = false)
    private int wrongAttempts;

    private String approvedByName;

    private Instant approvedAt;

    private String decisionNote;

    private String releasedByName;

    private Instant releasedAt;

    private String collectedByName;

    private Instant closedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected GatePass() {
    }

    GatePass(UUID studentId, PassSource source, Actor requestedBy, Instant pickupAt, String reason,
            CollectorKind collectorKind, String collectorName, String collectorPhone, Instant now) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.source = source;
        this.requestedByUserId = requestedBy.id();
        this.requestedByName = requestedBy.name();
        this.pickupAt = pickupAt;
        this.reason = reason;
        this.collectorKind = collectorKind;
        this.collectorName = collectorKind == CollectorKind.SELF ? null : collectorName;
        this.collectorPhone = collectorKind == CollectorKind.SELF ? null : collectorPhone;
        this.status = PassStatus.REQUESTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** The office approves: the OTP hash is stored and the clock starts. */
    void approve(String otpHash, Instant expiresAt, String note, Actor by, Instant now) {
        this.status = PassStatus.APPROVED;
        this.otpHash = otpHash;
        this.otpExpiresAt = expiresAt;
        this.wrongAttempts = 0;
        this.approvedByName = by.name();
        this.approvedAt = now;
        this.decisionNote = note;
    }

    void reject(String note, Actor by, Instant now) {
        this.status = PassStatus.REJECTED;
        this.approvedByName = by.name();
        this.approvedAt = now;
        this.decisionNote = note;
        this.closedAt = now;
    }

    void cancel(Instant now) {
        this.status = PassStatus.CANCELLED;
        this.closedAt = now;
    }

    void expire(Instant now) {
        this.status = PassStatus.EXPIRED;
        this.closedAt = now;
    }

    /** A wrong OTP at the gate; the fifth locks the pass. Returns true when it is now locked. */
    boolean wrongAttempt(Instant now) {
        wrongAttempts = Math.min(wrongAttempts + 1, MAX_WRONG_ATTEMPTS);
        if (wrongAttempts >= MAX_WRONG_ATTEMPTS) {
            status = PassStatus.LOCKED;
            closedAt = now;
            return true;
        }
        return false;
    }

    void release(String collectedByName, Actor by, Instant now) {
        this.status = PassStatus.RELEASED;
        this.releasedByName = by.name();
        this.releasedAt = now;
        this.collectedByName = collectedByName;
        this.closedAt = now;
    }

    boolean otpExpired(Instant now) {
        return otpExpiresAt != null && !now.isBefore(otpExpiresAt);
    }

    int attemptsLeft() {
        return Math.max(0, MAX_WRONG_ATTEMPTS - wrongAttempts);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    PassSource getSource() {
        return source;
    }

    UUID getRequestedByUserId() {
        return requestedByUserId;
    }

    String getRequestedByName() {
        return requestedByName;
    }

    Instant getPickupAt() {
        return pickupAt;
    }

    String getReason() {
        return reason;
    }

    CollectorKind getCollectorKind() {
        return collectorKind;
    }

    String getCollectorName() {
        return collectorName;
    }

    String getCollectorPhone() {
        return collectorPhone;
    }

    PassStatus getStatus() {
        return status;
    }

    String getOtpHash() {
        return otpHash;
    }

    Instant getOtpExpiresAt() {
        return otpExpiresAt;
    }

    int getWrongAttempts() {
        return wrongAttempts;
    }

    String getApprovedByName() {
        return approvedByName;
    }

    Instant getApprovedAt() {
        return approvedAt;
    }

    String getDecisionNote() {
        return decisionNote;
    }

    String getReleasedByName() {
        return releasedByName;
    }

    Instant getReleasedAt() {
        return releasedAt;
    }

    String getCollectedByName() {
        return collectedByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
