package com.akshara.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;

/**
 * One "forgot password" code sent to a person. Only a hash of the code is stored. It is usable until it expires, is
 * used, runs out of tries or is replaced by a newer request.
 */
@Entity
@Table(schema = "identity", name = "password_reset")
class PasswordReset extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String codeHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    private Instant usedAt;

    private Instant replacedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordReset() {
    }

    /** {@code id} comes first because the code's hash is salted with it ({@link PasswordResetService#hash}). */
    PasswordReset(UUID id, UUID userId, String codeHash, Instant now, Duration ttl) {
        this.id = id;
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = now;
        this.expiresAt = now.plus(ttl);
    }

    /** Still open: not used, not replaced, not expired and with tries left. */
    boolean isOpen(Instant now, int maxAttempts) {
        return usedAt == null && replacedAt == null && expiresAt.isAfter(now) && attempts < maxAttempts;
    }

    /** Counts a try; returns the number of tries used so far. */
    int recordAttempt() {
        return ++attempts;
    }

    void markUsed(Instant now) {
        this.usedAt = now;
    }

    void markReplaced(Instant now) {
        this.replacedAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getCodeHash() {
        return codeHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
