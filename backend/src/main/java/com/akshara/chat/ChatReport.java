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

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A parent or teacher flagging a conversation for the Principal, and its review. */
@Entity
@Table(schema = "chat", name = "report")
class ChatReport extends AssignedIdEntity {

    enum Status {
        OPEN, REVIEWED
    }

    /** What an erased student's report reason becomes. */
    static final String ERASED_REASON = "(erased)";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID conversationId;

    @Column(updatable = false)
    private UUID reportedById;

    @Column(nullable = false)
    private String reportedByName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Side reporterSide;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Instant reviewedAt;

    private UUID reviewedById;

    private String reviewedByName;

    private String reviewNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ChatReport() {
    }

    ChatReport(UUID conversationId, Actor by, Side side, String reason, Instant at) {
        this.id = Ids.newId();
        this.conversationId = conversationId;
        this.reportedById = by.id();
        this.reportedByName = by.name();
        this.reporterSide = side;
        this.reason = reason;
        this.status = Status.OPEN;
        this.createdAt = at;
        this.updatedAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void review(Actor by, String note, Instant at) {
        status = Status.REVIEWED;
        reviewedAt = at;
        reviewedById = by.id();
        reviewedByName = by.name();
        reviewNote = note;
    }

    void erase() {
        if (reporterSide == Side.PARENT) {
            reportedByName = ChatMessage.ERASED_PARENT;
        }
        reason = ERASED_REASON;
        reviewNote = reviewNote == null ? null : ERASED_REASON;
    }

    boolean isOpen() {
        return status == Status.OPEN;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getConversationId() {
        return conversationId;
    }

    UUID getReportedById() {
        return reportedById;
    }

    String getReportedByName() {
        return reportedByName;
    }

    Side getReporterSide() {
        return reporterSide;
    }

    String getReason() {
        return reason;
    }

    Status getStatus() {
        return status;
    }

    Instant getReviewedAt() {
        return reviewedAt;
    }

    String getReviewedByName() {
        return reviewedByName;
    }

    String getReviewNote() {
        return reviewNote;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
