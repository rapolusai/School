package com.akshara.admissions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ApplicationRepository extends JpaRepository<Application, UUID> {

    /** Reads and locks the row, so two admissions of the same application run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = ?1")
    Optional<Application> findForUpdate(UUID id);

    long countByStage(ApplicationStage stage);

    @Query("select count(a) from Application a where a.stage = ?1 and a.stageChangedAt >= ?2 and a.stageChangedAt < ?3")
    long countByStageChangedBetween(ApplicationStage stage, Instant from, Instant to);
}
