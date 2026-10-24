package com.akshara.platform;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PlatformRefreshTokenRepository extends JpaRepository<PlatformRefreshToken, UUID> {

    Optional<PlatformRefreshToken> findByTokenHash(String tokenHash);

    /** False once the chain has ended: sign-out and reuse detection revoke every live token in it. */
    boolean existsByFamilyIdAndRevokedAtIsNull(UUID familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PlatformRefreshToken t set t.revokedAt = ?2 where t.familyId = ?1 and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant at);
}
