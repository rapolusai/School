package com.akshara.notifications;

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

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One message to one recipient on one channel, from the moment it is queued until it is sent or given up on. */
@Entity
@Table(schema = "notifications", name = "message")
class Message extends AssignedIdEntity {

    static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Channel channel;

    @Column(nullable = false, updatable = false)
    private String recipient;

    private String recipientName;

    @Column(nullable = false, updatable = false)
    private String templateKey;

    @Column(nullable = false, updatable = false)
    private String language;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String params;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status;

    @Column(nullable = false)
    private int attempts;

    private String lastError;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Enumerated(EnumType.STRING)
    private Channel fallbackChannel;

    private String relatedType;

    private UUID relatedId;

    private String relatedLabel;

    private String dedupeKey;

    private String providerRef;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant sentAt;

    @Version
    private long version;

    protected Message() {
    }

    Message(Channel channel, String recipient, String recipientName, String templateKey, String language,
            String params, String body, Channel fallbackChannel, String relatedType, UUID relatedId,
            String relatedLabel, String dedupeKey, Instant at) {
        this.id = Ids.newId();
        this.channel = channel;
        this.recipient = recipient;
        this.recipientName = recipientName;
        this.templateKey = templateKey;
        this.language = language;
        this.params = params;
        this.body = body;
        this.status = MessageStatus.QUEUED;
        this.fallbackChannel = fallbackChannel;
        this.relatedType = relatedType;
        this.relatedId = relatedId;
        this.relatedLabel = relatedLabel;
        this.dedupeKey = dedupeKey;
        this.createdAt = at;
        this.updatedAt = at;
        this.nextAttemptAt = at;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Puts a skipped message back in the queue with fresh content, for example when a student is absent again. */
    void requeue(String recipientName, String params, String body, Instant at) {
        this.recipientName = recipientName;
        this.params = params;
        this.body = body;
        this.status = MessageStatus.QUEUED;
        this.attempts = 0;
        this.lastError = null;
        this.nextAttemptAt = at;
    }

    /** No longer needed. Only a message that is still waiting can be skipped. */
    boolean skip(String reason) {
        if (status != MessageStatus.QUEUED) {
            return false;
        }
        status = MessageStatus.SKIPPED;
        lastError = truncate(reason);
        return true;
    }

    /** Waits, without using an attempt, until the given time (quiet hours). */
    void holdUntil(Instant until) {
        nextAttemptAt = until;
    }

    void delivered(MessageStatus result, String providerRef, Instant at) {
        attempts++;
        status = result;
        this.providerRef = providerRef;
        lastError = null;
        sentAt = at;
    }

    /** A failed attempt: tries again at {@code retryAt}, or gives up when that is null. */
    void failed(String error, Instant retryAt) {
        attempts++;
        lastError = truncate(error);
        if (retryAt == null) {
            status = MessageStatus.FAILED;
        } else {
            nextAttemptAt = retryAt;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    Channel getChannel() {
        return channel;
    }

    String getRecipient() {
        return recipient;
    }

    String getRecipientName() {
        return recipientName;
    }

    String getTemplateKey() {
        return templateKey;
    }

    String getLanguage() {
        return language;
    }

    String getParams() {
        return params;
    }

    String getBody() {
        return body;
    }

    MessageStatus getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }

    String getLastError() {
        return lastError;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    Channel getFallbackChannel() {
        return fallbackChannel;
    }

    String getRelatedType() {
        return relatedType;
    }

    UUID getRelatedId() {
        return relatedId;
    }

    String getRelatedLabel() {
        return relatedLabel;
    }

    String getDedupeKey() {
        return dedupeKey;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getSentAt() {
        return sentAt;
    }
}
