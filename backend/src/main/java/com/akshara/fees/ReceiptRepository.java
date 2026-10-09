package com.akshara.fees;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receipt r where r.id = ?1")
    Optional<Receipt> lock(UUID id);

    List<Receipt> findByStudentIdOrderByReceivedAtDesc(UUID studentId);

    List<Receipt> findAllByOrderByReceivedAtDesc(Limit limit);

    @Query("select r.mode, count(r), sum(r.amountPaise) from Receipt r where r.status = 'ISSUED' "
            + "and r.receivedOn between ?1 and ?2 group by r.mode")
    List<Object[]> collectedPerMode(LocalDate from, LocalDate to);

    @Query("select count(r) from Receipt r where r.status = 'CANCELLED' and r.receivedOn between ?1 and ?2")
    long cancelledBetween(LocalDate from, LocalDate to);

    @Query("select r from Receipt r where r.receivedOn between ?1 and ?2 order by r.receivedAt, r.receiptNo")
    List<Receipt> issuedBetween(LocalDate from, LocalDate to);

    @Query("select r from Receipt r where r.status = 'CANCELLED' and r.cancelledAt >= ?1 and r.cancelledAt < ?2 "
            + "order by r.cancelledAt")
    List<Receipt> cancelledDuring(Instant from, Instant until);
}
