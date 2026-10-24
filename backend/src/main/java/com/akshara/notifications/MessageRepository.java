package com.akshara.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Messages about one thing (for example a circular) that are still waiting to be sent. */
    @Query("select m from Message m where m.relatedType = ?1 and m.relatedId = ?2 "
            + "and m.status = com.akshara.notifications.MessageStatus.QUEUED")
    List<Message> queuedAbout(String relatedType, UUID relatedId);

    /**
     * Clears the log entries about one student (absence alerts, fee messages and the like): recipient, name, text and
     * parameters go, channel, template, status, attempts and dates stay for the statistics. A message still waiting
     * is skipped. Row-level security keeps it to the current school.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update notifications.message set recipient = '" + MessageErasure.ERASED + "', "
            + "recipient_name = null, body = '', params = '{}'::jsonb, related_label = :label, "
            + "status = case when status = 'QUEUED' then 'SKIPPED' else status end, "
            + "last_error = case when status = 'QUEUED' then :reason else last_error end, "
            + "updated_at = now(), version = version + 1 "
            + "where related_type = 'student' and related_id = :studentId and recipient <> '" + MessageErasure.ERASED
            + "'", nativeQuery = true)
    int eraseAboutStudent(@Param("studentId") UUID studentId, @Param("label") String label,
            @Param("reason") String reason);

    /**
     * Clears every log entry sent to these phone numbers (compared by their last ten digits) or emails, except those
     * about another student, the same way as {@link #eraseAboutStudent}.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update notifications.message set recipient = '" + MessageErasure.ERASED + "', "
            + "recipient_name = null, body = '', params = '{}'::jsonb, "
            + "status = case when status = 'QUEUED' then 'SKIPPED' else status end, "
            + "last_error = case when status = 'QUEUED' then :reason else last_error end, "
            + "updated_at = now(), version = version + 1 "
            + "where recipient <> '" + MessageErasure.ERASED + "' "
            + "and (right(regexp_replace(recipient, '[^0-9]', '', 'g'), 10) in (:phones) "
            + "or lower(recipient) in (:emails)) "
            + "and not (related_type = 'student' and related_id is not null and related_id <> :studentId)",
            nativeQuery = true)
    int eraseSentTo(@Param("studentId") UUID studentId, @Param("phones") Collection<String> phones,
            @Param("emails") Collection<String> emails, @Param("reason") String reason);
}
