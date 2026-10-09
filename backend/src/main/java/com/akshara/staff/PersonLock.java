package com.akshara.staff;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serialises changes to one staff member's leave and attendance, so two requests sent at once (a double tap on "Check
 * in", two leave applications) cannot both pass the checks. A transaction-scoped Postgres advisory lock: released at
 * commit or rollback, and taking it again in the same transaction is fine.
 */
@Component
class PersonLock {

    private final EntityManager entityManager;

    PersonLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void lock(UUID userId) {
        long key = userId.getMostSignificantBits() ^ userId.getLeastSignificantBits();
        entityManager.createNativeQuery("select count(*) from (select pg_advisory_xact_lock(?1)) l")
                .setParameter(1, key)
                .getSingleResult();
    }
}
