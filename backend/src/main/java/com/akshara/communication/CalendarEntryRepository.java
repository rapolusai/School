package com.akshara.communication;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.EntryKind;

interface CalendarEntryRepository extends JpaRepository<CalendarEntry, UUID> {

    /** Entries with any day between the two dates (both included), in date order. */
    @Query("select e from CalendarEntry e where e.startsOn <= ?2 and e.endsOn >= ?1 "
            + "order by e.startsOn, e.startTime asc nulls first, e.title, e.id")
    List<CalendarEntry> findOverlapping(LocalDate from, LocalDate to);

    /** Entries that have not ended by {@code from}, soonest first. */
    @Query("select e from CalendarEntry e where e.endsOn >= ?1 "
            + "order by e.startsOn, e.startTime asc nulls first, e.title, e.id")
    List<CalendarEntry> findFrom(LocalDate from, Limit limit);

    /** Entries of a kind and audience with any day between the two dates (both included). */
    @Query("select e from CalendarEntry e where e.kind = ?1 and e.audience = ?2 "
            + "and e.startsOn <= ?4 and e.endsOn >= ?3 order by e.startsOn, e.title, e.id")
    List<CalendarEntry> findKindBetween(EntryKind kind, CalendarAudience audience, LocalDate from, LocalDate to);

    @Query("select e.startsOn from CalendarEntry e where e.kind = ?1 and e.startsOn between ?2 and ?3")
    List<LocalDate> startDatesOf(EntryKind kind, LocalDate from, LocalDate to);

    /** Entries whose reminder is due on {@code today}: the lead time has started and the entry has not begun yet. */
    @Query(value = "select id from communication.calendar_entry where reminder_days is not null "
            + "and reminder_sent_at is null and starts_on - reminder_days <= ?1 and starts_on >= ?1 "
            + "order by starts_on, id limit ?2", nativeQuery = true)
    List<UUID> dueReminderIds(LocalDate today, int limit);

    /** Holds an entry whose reminder is due; empty when it is gone, not due, or another sender has it. */
    @Query(value = "select * from communication.calendar_entry where id = ?1 and reminder_days is not null "
            + "and reminder_sent_at is null and starts_on - reminder_days <= ?2 and starts_on >= ?2 "
            + "for update skip locked", nativeQuery = true)
    Optional<CalendarEntry> lockDueReminder(UUID id, LocalDate today);

    /** Schools with reminders due. Works without a school selected (see V7). */
    @Query(value = "select tenant_id from communication.tenants_with_due_reminders(?1, ?2)", nativeQuery = true)
    List<UUID> tenantsWithDueReminders(LocalDate today, int limit);
}
