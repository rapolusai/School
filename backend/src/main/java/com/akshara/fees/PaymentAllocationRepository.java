package com.akshara.fees;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

    List<PaymentAllocation> findByReceiptIdOrderByLineNoAscEntryAsc(UUID receiptId);

    List<PaymentAllocation> findByReceiptIdInOrderByLineNoAscEntryAsc(Collection<UUID> receiptIds);

    /** Late fees already charged per instalment of a student, net of cancelled receipts. */
    @Query("select a.instalmentId, sum(a.amountPaise) from PaymentAllocation a where a.studentId = ?1 "
            + "and a.kind = 'LATE_FEE' group by a.instalmentId")
    List<Object[]> lateFeesCharged(UUID studentId);

    @Query("select a.studentId, a.instalmentId, sum(a.amountPaise) from PaymentAllocation a "
            + "where a.studentId in ?1 and a.kind = 'LATE_FEE' group by a.studentId, a.instalmentId")
    List<Object[]> lateFeesCharged(Collection<UUID> studentIds);

    boolean existsByDueIdIn(Collection<UUID> dueIds);

    /** Dues that ever had money allocated to them, even if the receipt was later cancelled. */
    @Query("select distinct a.dueId from PaymentAllocation a where a.dueId in ?1")
    List<UUID> dueIdsWithHistory(Collection<UUID> dueIds);

    boolean existsByInstalmentIdIn(Collection<UUID> instalmentIds);

    boolean existsByHeadId(UUID headId);

    /** Fee heads collected by receipts issued (and not cancelled) between two days. */
    @Query("select a.headId, sum(a.amountPaise) from PaymentAllocation a join Receipt r on r.id = a.receiptId "
            + "where r.status = 'ISSUED' and a.entry = 'ALLOCATION' and a.kind = 'DUE' "
            + "and r.receivedOn between ?1 and ?2 group by a.headId")
    List<Object[]> collectedPerHead(LocalDate from, LocalDate to);

    /** Late fees and advances collected by receipts issued (and not cancelled) between two days. */
    @Query("select a.kind, sum(a.amountPaise) from PaymentAllocation a join Receipt r on r.id = a.receiptId "
            + "where r.status = 'ISSUED' and a.entry = 'ALLOCATION' and r.receivedOn between ?1 and ?2 group by a.kind")
    List<Object[]> collectedPerKind(LocalDate from, LocalDate to);
}
