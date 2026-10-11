package com.akshara.chat;

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

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One conversation between a parent and a member of staff about one student. Closed by the Principal (read-only) and
 * reopened the same way; erased with the student's data.
 */
@Entity
@Table(schema = "chat", name = "conversation")
class Conversation extends AssignedIdEntity {

    enum Status {
        OPEN, CLOSED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private UUID parentUserId;

    @Column(nullable = false, updatable = false)
    private UUID staffUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Side startedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Instant closedAt;

    private String closedByName;

    private String closeReason;

    private Instant erasedAt;

    private Instant lastMessageAt;

    @Column(nullable = false)
    private int messageCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Conversation() {
    }

    Conversation(UUID studentId, UUID parentUserId, UUID staffUserId, Side startedBy, Instant at) {
        this.id = Ids.newId();
        this.studentId = studentId;
        this.parentUserId = parentUserId;
        this.staffUserId = staffUserId;
        this.startedBy = startedBy;
        this.status = Status.OPEN;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void messageAdded(Instant at) {
        messageCount++;
        lastMessageAt = at;
    }

    void close(String byName, String reason, Instant at) {
        status = Status.CLOSED;
        closedAt = at;
        closedByName = byName;
        closeReason = reason;
    }

    void reopen() {
        status = Status.OPEN;
        closedAt = null;
        closedByName = null;
        closeReason = null;
    }

    void erase(Instant at) {
        erasedAt = at;
        closeReason = null;
    }

    boolean isClosed() {
        return status == Status.CLOSED;
    }

    boolean isErased() {
        return erasedAt != null;
    }

    /** The user id of a participant on the given side. */
    UUID participant(Side side) {
        return side == Side.PARENT ? parentUserId : staffUserId;
    }

    /** The side the user is on, or null when they are not part of this conversation. */
    Side sideOf(UUID userId) {
        if (parentUserId.equals(userId)) {
            return Side.PARENT;
        }
        if (staffUserId.equals(userId)) {
            return Side.STAFF;
        }
        return null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getStudentId() {
        return studentId;
    }

    UUID getParentUserId() {
        return parentUserId;
    }

    UUID getStaffUserId() {
        return staffUserId;
    }

    Side getStartedBy() {
        return startedBy;
    }

    Status getStatus() {
        return status;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    String getClosedByName() {
        return closedByName;
    }

    String getCloseReason() {
        return closeReason;
    }

    Instant getErasedAt() {
        return erasedAt;
    }

    Instant getLastMessageAt() {
        return lastMessageAt;
    }

    int getMessageCount() {
        return messageCount;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
