package com.akshara.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Online payment orders of the selected school only (row-level security and the tenant filter). */
interface InvoiceOrderRepository extends JpaRepository<InvoiceOrder, UUID> {

    /** Locks the order so the browser's verification and the webhook record it one after the other, never twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from InvoiceOrder o where o.id = ?1")
    Optional<InvoiceOrder> lock(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from InvoiceOrder o where o.gateway = ?1 and o.gatewayOrderId = ?2")
    Optional<InvoiceOrder> lockByGatewayOrder(String gateway, String gatewayOrderId);

    Optional<InvoiceOrder> findByGatewayAndGatewayOrderId(String gateway, String gatewayOrderId);

    List<InvoiceOrder> findByInvoiceIdAndStatus(UUID invoiceId, String status);
}
