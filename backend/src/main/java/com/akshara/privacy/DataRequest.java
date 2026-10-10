package com.akshara.privacy;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A parent's request about their own or their child's personal data: access, correction, erasure or a grievance. Staff
 * answer it by the due date (30 days, the school's policy), and every step is kept on its timeline.
 */
@Entity
@Table(schema = "privacy", name = "data_request")
class DataRequest extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RequestSubject subject;

    @Column(updatable = false)
    private UUID studentId;

    @Column(updatable = false)
    private UUID requestedById;

    @Column(nullable = false, updatable = false)
    private String requesterName;

    @Column(nullable = false, updatable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDate dueOn;

    private UUID assignedToId;

    private String assignedToName;

    @Enumerated(EnumType.STRING)
    private RequestResolution resolution;

    private String closingNote;

    private Instant closedAt;

    private UUID closedById;

    private String closedByName;

    private Instant erasedAt;

    @Column(nullable = false)
    private Instant lastActivityAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected DataRequest() {
    }

    DataRequest(RequestType type, RequestSubject subject, UUID studentId, UUID requestedById, String requesterName,
            String details, LocalDate dueOn) {
        this.id = Ids.newId();
        this.type = type;
        this.subject = subject;
        this.studentId = studentId;
        this.requestedById = requestedById;
        this.requesterName = requesterName;
        this.details = details;
        this.dueOn = dueOn;
        this.status = RequestStatus.SUBMITTED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.lastActivityAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    boolean isClosed() {
        return status == RequestStatus.CLOSED;
    }

    /** Something happened: the request is being worked on from now on. */
    void activity(Instant at) {
        lastActivityAt = at;
        if (status == RequestStatus.SUBMITTED) {
            status = RequestStatus.IN_PROGRESS;
        }
    }

    /** A parent's reply only updates the time; it does not change who is working on it. */
    void touchedBy(Instant at) {
        lastActivityAt = at;
    }

    void assign(UUID userId, String name, Instant at) {
        this.assignedToId = userId;
        this.assignedToName = name;
        activity(at);
    }

    void markErased(Instant at) {
        this.erasedAt = at;
        activity(at);
    }

    void close(RequestResolution resolution, String note, UUID byId, String byName, Instant at) {
        this.status = RequestStatus.CLOSED;
        this.resolution = resolution;
        this.closingNote = note;
        this.closedAt = at;
        this.closedById = byId;
        this.closedByName = byName;
        this.lastActivityAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    RequestType getType() {
        return type;
    }

    RequestSubject getSubject() {
        return subject;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getRequestedById() {
        return requestedById;
    }

    String getRequesterName() {
        return requesterName;
    }

    String getDetails() {
        return details;
    }

    RequestStatus getStatus() {
        return status;
    }

    LocalDate getDueOn() {
        return dueOn;
    }

    UUID getAssignedToId() {
        return assignedToId;
    }

    String getAssignedToName() {
        return assignedToName;
    }

    RequestResolution getResolution() {
        return resolution;
    }

    String getClosingNote() {
        return closingNote;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    String getClosedByName() {
        return closedByName;
    }

    Instant getErasedAt() {
        return erasedAt;
    }

    Instant getLastActivityAt() {
        return lastActivityAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
