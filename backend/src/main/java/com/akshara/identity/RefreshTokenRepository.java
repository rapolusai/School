package com.akshara.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** False once the chain has ended: sign-out and reuse detection revoke every live token in it. */
    boolean existsByFamilyIdAndRevokedAtIsNull(UUID familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = ?2 where t.familyId = ?1 and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant at);

    /** The session chains of one person that are still open (their access tokens may still be alive). */
    @Query("select distinct t.familyId from RefreshToken t where t.userId = ?1 and t.revokedAt is null")
    List<UUID> openFamiliesOf(UUID userId);

    /**
     * Ends every session of one person, for example when they leave the school. Does not clear the caller's
     * persistence context, so entities it already loaded stay managed.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = ?2 where t.userId = ?1 and t.revokedAt is null")
    int revokeAllOf(UUID userId, Instant at);
}
