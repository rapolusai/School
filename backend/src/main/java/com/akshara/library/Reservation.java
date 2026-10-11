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
 * A member's hold on a title whose copies are all out. Members wait in the order they asked (WAITING); when a copy
 * comes back it is kept for the first one (READY) until {@code holdUntil}, then it passes to the next member.
 */
@Entity
@Table(schema = "library", name = "reservation")
class Reservation extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

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
    @Column(nullable = false)
    private ReservationStatus status;

    private UUID copyId;

    private Instant readyAt;

    private LocalDate holdUntil;

    private Instant closedAt;

    @Column(updatable = false)
    private UUID createdById;

    @Column(updatable = false)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Reservation() {
    }

    Reservation(UUID titleId, MemberRef member, Actor by) {
        this.id = Ids.newId();
        this.titleId = titleId;
        this.memberType = member.type();
        this.studentId = member.studentId();
        this.staffUserId = member.staffUserId();
        this.status = ReservationStatus.WAITING;
        this.createdById = by.id();
        this.createdByName = by.name();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    boolean isActive() {
        return status.isActive();
    }

    void ready(UUID copyId, LocalDate holdUntil, Instant at) {
        this.status = ReservationStatus.READY;
        this.copyId = copyId;
        this.holdUntil = holdUntil;
        this.readyAt = at;
    }

    void close(ReservationStatus to, Instant at) {
        this.status = to;
        this.closedAt = at;
    }

    MemberRef member() {
        return MemberRef.of(memberType, studentId, staffUserId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTitleId() {
        return titleId;
    }

    MemberType getMemberType() {
        return memberType;
    }

    ReservationStatus getStatus() {
        return status;
    }

    UUID getCopyId() {
        return copyId;
    }

    Instant getReadyAt() {
        return readyAt;
    }

    LocalDate getHoldUntil() {
        return holdUntil;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    String getCreatedByName() {
        return createdByName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
