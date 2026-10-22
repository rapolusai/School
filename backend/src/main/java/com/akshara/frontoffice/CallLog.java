package com.akshara.frontoffice;

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

/** A phone call the office took or made, with its outcome and a follow-up date. */
@Entity
@Table(schema = "frontoffice", name = "call_log")
class CallLog extends AssignedIdEntity {

    record Details(CallDirection direction, String callerName, String phone, String about, String outcome,
            LocalDate followUpOn, UUID assignedToUserId, String assignedToName, Instant calledAt) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CallDirection direction;

    @Column(nullable = false)
    private String callerName;

    private String phone;

    @Column(nullable = false)
    private String about;

    private String outcome;

    private LocalDate followUpOn;

    private UUID assignedToUserId;

    private String assignedToName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CallStatus status;

    @Column(updatable = false)
    private String loggedByName;

    @Column(nullable = false)
    private Instant calledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CallLog() {
    }

    CallLog(Details d, Actor by, Instant now) {
        this.id = Ids.newId();
        apply(d);
        this.status = d.followUpOn() == null ? CallStatus.DONE : CallStatus.OPEN;
        this.loggedByName = by.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void apply(Details d) {
        this.direction = d.direction();
        this.callerName = d.callerName();
        this.phone = d.phone();
        this.about = d.about();
        this.outcome = d.outcome();
        this.followUpOn = d.followUpOn();
        this.assignedToUserId = d.assignedToUserId();
        this.assignedToName = d.assignedToName();
        this.calledAt = d.calledAt();
    }

    void close(String outcome) {
        this.status = CallStatus.DONE;
        if (outcome != null) {
            this.outcome = outcome;
        }
    }

    void reopen() {
        this.status = CallStatus.OPEN;
    }

    @Override
    public UUID getId() {
        return id;
    }

    CallDirection getDirection() {
        return direction;
    }

    String getCallerName() {
        return callerName;
    }

    String getPhone() {
        return phone;
    }

    String getAbout() {
        return about;
    }

    String getOutcome() {
        return outcome;
    }

    LocalDate getFollowUpOn() {
        return followUpOn;
    }

    UUID getAssignedToUserId() {
        return assignedToUserId;
    }

    String getAssignedToName() {
        return assignedToName;
    }

    CallStatus getStatus() {
        return status;
    }

    String getLoggedByName() {
        return loggedByName;
    }

    Instant getCalledAt() {
        return calledAt;
    }
}
