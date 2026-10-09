package com.akshara.communication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.akshara.communication.CommunicationTypes.Status;

interface CircularRepository extends JpaRepository<Circular, UUID> {

    /** Schools with scheduled circulars due, the longest-waiting first. Works without a school selected (see V7). */
    @Query(value = "select tenant_id from communication.tenants_with_due_circulars(?1, ?2)", nativeQuery = true)
    List<UUID> tenantsWithDueCirculars(Instant now, int limit);

    /** Holds the circular for a change of state, so two people (or the scheduler) never move it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Circular c where c.id = ?1")
    Optional<Circular> lockById(UUID id);

    List<Circular> findAllByOrderByUpdatedAtDescIdAsc(Limit limit);

    List<Circular> findByStatusOrderByUpdatedAtDescIdAsc(Status status, Limit limit);

    List<Circular> findByCreatedByIdOrderByUpdatedAtDescIdAsc(UUID createdById, Limit limit);

    List<Circular> findByStatusAndCreatedByIdOrderByUpdatedAtDescIdAsc(Status status, UUID createdById, Limit limit);

    /** Circulars per status: rows of (status, count). */
    @Query("select c.status, count(c) from Circular c group by c.status")
    List<Object[]> countPerStatus();

    /** One person's circulars per status: rows of (status, count). */
    @Query("select c.status, count(c) from Circular c where c.createdById = ?1 group by c.status")
    List<Object[]> countPerStatusOf(UUID createdById);

    /** Circulars in {@code status} whose scheduled time has come, the longest-waiting first. */
    @Query("select c.id from Circular c where c.status = ?1 and c.scheduledAt <= ?2 order by c.scheduledAt, c.id")
    List<UUID> dueIds(Status status, Instant now, Limit limit);

    /** Holds a scheduled circular that is due, for sending; empty when it is gone, not due, or another sender has it. */
    @Query(value = "select * from communication.circular where id = ?1 and status = 'SCHEDULED' "
            + "and scheduled_at <= ?2 for update skip locked", nativeQuery = true)
    Optional<Circular> lockDue(UUID id, Instant now);
}
