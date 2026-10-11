package com.akshara.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One message in a conversation. Never edited; the sender may delete it for both sides within
 * {@link ChatRules#DELETE_WINDOW}, which only marks it (the text stays for oversight). {@code updatedAt} moves on every
 * change, so an open chat asks only for what changed since it last looked.
 */
@Entity
@Table(schema = "chat", name = "message")
class ChatMessage extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID conversationId;

    @Column(updatable = false)
    private UUID senderUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Side senderSide;

    @Column(nullable = false)
    private String senderName;

    private String body;

    @Column(nullable = false)
    private int attachmentCount;

    private Instant readAt;

    private Instant deletedAt;

    private Instant erasedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** The name a parent's messages carry once the student's data is erased. */
    static final String ERASED_PARENT = "Parent";

    protected ChatMessage() {
    }

    ChatMessage(UUID conversationId, UUID senderUserId, Side senderSide, String senderName, String body,
            int attachmentCount, Instant at) {
        this.id = Ids.newId();
        this.conversationId = conversationId;
        this.senderUserId = senderUserId;
        this.senderSide = senderSide;
        this.senderName = senderName;
        this.body = body;
        this.attachmentCount = attachmentCount;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** The other side saw it. Returns true when this changed the message. */
    boolean markRead(Instant at) {
        if (readAt != null) {
            return false;
        }
        readAt = at;
        updatedAt = at;
        return true;
    }

    void delete(Instant at) {
        deletedAt = at;
        updatedAt = at;
    }

    /** Clears the text and the parent's name (the attachments go separately); the placeholder stays. */
    void erase(Instant at) {
        if (senderSide == Side.PARENT) {
            senderName = ERASED_PARENT;
        }
        body = null;
        attachmentCount = 0;
        erasedAt = at;
        updatedAt = at;
    }

    boolean isDeleted() {
        return deletedAt != null;
    }

    boolean isErased() {
        return erasedAt != null;
    }

    /** The last moment the sender may still delete it. */
    Instant deletableUntil() {
        return createdAt.plus(ChatRules.DELETE_WINDOW);
    }

    boolean deletableAt(Instant now) {
        return deletedAt == null && erasedAt == null && !now.isAfter(deletableUntil());
    }

    /** How long ago it was sent. */
    Duration age(Instant now) {
        return Duration.between(createdAt, now);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getConversationId() {
        return conversationId;
    }

    UUID getSenderUserId() {
        return senderUserId;
    }

    Side getSenderSide() {
        return senderSide;
    }

    String getSenderName() {
        return senderName;
    }

    String getBody() {
        return body;
    }

    int getAttachmentCount() {
        return attachmentCount;
    }

    Instant getReadAt() {
        return readAt;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    Instant getErasedAt() {
        return erasedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
