package com.akshara.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    /** The person's codes that are neither used nor replaced, newest first (at most one is ever open). */
    @Query("select r from PasswordReset r where r.userId = ?1 and r.usedAt is null and r.replacedAt is null "
            + "order by r.createdAt desc")
    List<PasswordReset> findUnfinished(UUID userId);

    /** The newest unfinished code, locked so two confirmations at once count their tries one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PasswordReset r where r.userId = ?1 and r.usedAt is null and r.replacedAt is null "
            + "order by r.createdAt desc limit 1")
    Optional<PasswordReset> lockLatestUnfinished(UUID userId);
}
