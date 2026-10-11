package com.akshara.library;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface StockCheckRepository extends JpaRepository<StockCheck, UUID> {

    @Query("select s from StockCheck s where s.status = com.akshara.library.StockCheckStatus.OPEN")
    Optional<StockCheck> findOpen();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockCheck s where s.id = ?1")
    Optional<StockCheck> lockById(UUID id);

    @Query("select s from StockCheck s order by s.startedAt desc, s.id desc")
    List<StockCheck> newestFirst();
}
