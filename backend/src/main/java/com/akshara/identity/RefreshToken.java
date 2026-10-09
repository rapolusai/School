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
import com.akshara.shared.Ids;

/**
 * One link in a chain of rotated refresh tokens. Only the SHA-256 hash of the cookie value is stored. Every rotation
 * keeps the family id, so reuse of an old token can revoke the whole chain.
 */
@Entity
@Table(schema = "identity", name = "refresh_token")
public class RefreshToken extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID familyId;

    @Column(nullable = false, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant revokedAt;

    private UUID replacedBy;

    protected RefreshToken() {
    }

    RefreshToken(UUID userId, UUID familyId, String tokenHash, Duration ttl) {
        this.id = Ids.newId();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plus(ttl);
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    /** True when this token was swapped for a newer one moments ago, as happens when two tabs refresh at once. */
    boolean wasJustRotated(Instant now, Duration grace) {
        return replacedBy != null && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    void rotateTo(RefreshToken next) {
        this.revokedAt = Instant.now();
        this.replacedBy = next.id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getFamilyId() {
        return familyId;
    }
}
