package com.akshara.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Invoices of the selected school only: row-level security and the tenant filter both apply. */
interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = ?1")
    Optional<Invoice> lock(UUID id);

    @Query("select i from Invoice i order by i.invoiceDate desc, i.financialYear desc, i.seq desc")
    List<Invoice> newestFirst();

    @Query("select count(i) > 0 from Invoice i where i.periodStart = ?1 and i.status <> 'CANCELLED'")
    boolean existsLiveForPeriod(LocalDate periodStart);

    @Query("select i from Invoice i where i.status = 'ISSUED' order by i.dueDate")
    List<Invoice> unpaid();
}
