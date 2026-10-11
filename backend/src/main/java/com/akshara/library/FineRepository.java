package com.akshara.library;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface FineRepository extends JpaRepository<Fine, UUID> {

    /** Reads and locks the fines, so one fine is never collected twice or collected while it is waived. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id in ?1")
    List<Fine> lockByIds(Collection<UUID> ids);

    @Query("select f from Fine f where f.studentId = ?1 order by f.createdAt desc, f.id")
    List<Fine> ofStudent(UUID studentId);

    @Query("select f from Fine f where f.staffUserId = ?1 order by f.createdAt desc, f.id")
    List<Fine> ofStaff(UUID userId);

    @Query("select f from Fine f where f.studentId in ?1 order by f.createdAt desc, f.id")
    List<Fine> ofStudents(Collection<UUID> studentIds);

    @Query("select f from Fine f where f.receiptNo = ?1 order by f.createdAt, f.id")
    List<Fine> byReceipt(String receiptNo);

    @Query("select f from Fine f where f.loanId in ?1 order by f.createdAt, f.id")
    List<Fine> ofLoans(Collection<UUID> loanIds);

    @Query("select coalesce(sum(f.amountPaise), 0) from Fine f where f.status = com.akshara.library.FineStatus.DUE")
    long totalDue();
}
