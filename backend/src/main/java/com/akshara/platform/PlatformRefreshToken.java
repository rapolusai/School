package com.akshara.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * One link in a chain of rotated refresh tokens of a Super Admin, like a school user's {@code identity.refresh_token}
 * but with no school. Only the SHA-256 hash of the cookie value is stored.
 */
@Entity
@Table(schema = "platform", name = "platform_refresh_token")
class PlatformRefreshToken extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID adminId;

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

    protected PlatformRefreshToken() {
    }

    PlatformRefreshToken(UUID adminId, UUID familyId, String tokenHash, Duration ttl) {
        this.id = Ids.newId();
        this.adminId = adminId;
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

    /** True when this token was swapped for a newer one moments ago (two tabs, or a lost response). */
    boolean wasJustRotated(Instant now, Duration grace) {
        return replacedBy != null && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    void rotateTo(PlatformRefreshToken next) {
        this.revokedAt = Instant.now();
        this.replacedBy = next.id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getAdminId() {
        return adminId;
    }

    UUID getFamilyId() {
        return familyId;
    }
}
