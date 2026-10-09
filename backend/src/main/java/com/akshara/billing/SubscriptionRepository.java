package com.akshara.billing;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByTenantId(UUID tenantId);

    /** The school's row, locked until the transaction ends: billing changes of one school run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.tenantId = ?1")
    Optional<Subscription> lockByTenantId(UUID tenantId);
}
