package com.akshara.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MessageRepository extends JpaRepository<Message, UUID> {

    /** Schools with messages due, the longest-waiting first. Works without a school selected (see V5). */
    @Query(value = "select tenant_id from notifications.tenants_with_due_messages(?1, ?2)", nativeQuery = true)
    List<UUID> tenantsWithDueMessages(Instant now, int limit);

    @Query("select m.id from Message m where m.status = com.akshara.notifications.MessageStatus.QUEUED "
            + "and m.nextAttemptAt <= ?1 order by m.nextAttemptAt, m.id")
    List<UUID> dueIds(Instant now, Limit limit);

    /** Locks a due message for sending; empty when it is gone, not due, or another sender holds it. */
    @Query(value = "select * from notifications.message where id = ?1 and status = 'QUEUED' "
            + "and next_attempt_at <= ?2 for update skip locked", nativeQuery = true)
    Optional<Message> lockDue(UUID id, Instant now);

    Optional<Message> findByDedupeKey(String dedupeKey);

    List<Message> findByDedupeKeyIn(Collection<String> dedupeKeys);
}
