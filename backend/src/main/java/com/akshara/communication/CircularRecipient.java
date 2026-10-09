package com.akshara.communication;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.communication.CommunicationTypes.RecipientKind;
import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A person with a sign-in who has a sent circular on their notice board, and when they first read it. */
@Entity
@Table(schema = "communication", name = "circular_recipient")
class CircularRecipient extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID circularId;

    @Column(name = "user_account_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RecipientKind kind;

    private Instant readAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CircularRecipient() {
    }

    CircularRecipient(UUID circularId, UUID userId, RecipientKind kind, Instant at) {
        this.id = Ids.newId();
        this.circularId = circularId;
        this.userId = userId;
        this.kind = kind;
        this.createdAt = at;
    }

    /** Records the first reading; later ones change nothing. Returns true when this was the first. */
    boolean read(Instant at) {
        if (readAt != null) {
            return false;
        }
        readAt = at;
        return true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getCircularId() {
        return circularId;
    }

    UUID getUserId() {
        return userId;
    }

    RecipientKind getKind() {
        return kind;
    }

    Instant getReadAt() {
        return readAt;
    }
}
