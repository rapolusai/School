package com.akshara.fees;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    /** Locks the order so the browser's verification and the webhook record it one after the other, never twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.id = ?1")
    Optional<PaymentOrder> lock(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.gateway = ?1 and o.gatewayOrderId = ?2")
    Optional<PaymentOrder> lockByGatewayOrder(String gateway, String gatewayOrderId);

    Optional<PaymentOrder> findByGatewayAndGatewayOrderId(String gateway, String gatewayOrderId);
}
