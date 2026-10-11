package com.akshara.chat;

/*
 * The chat module's Spring Data repositories, one interface per table. Kept in one file because each is a few queries;
 * all are package-private and run as the current school.
 */

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ChatSettingsRepository extends JpaRepository<ChatSettings, UUID> {

    Optional<ChatSettings> findFirstBy();
}

interface StaffAwayRepository extends JpaRepository<StaffAway, UUID> {

    Optional<StaffAway> findByUserId(UUID userId);

    List<StaffAway> findByUserIdIn(Collection<UUID> userIds);
}

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = ?1")
    Optional<Conversation> lock(UUID id);

    Optional<Conversation> findByStudentIdAndParentUserIdAndStaffUserId(UUID studentId, UUID parentUserId,
            UUID staffUserId);

    @Query("select c from Conversation c where c.staffUserId = ?1 "
            + "order by c.lastMessageAt desc nulls last, c.createdAt desc")
    List<Conversation> ofStaff(UUID staffUserId);

    @Query("select c from Conversation c where c.parentUserId = ?1 "
            + "order by c.lastMessageAt desc nulls last, c.createdAt desc")
    List<Conversation> ofParent(UUID parentUserId);

    List<Conversation> findByStudentId(UUID studentId);

    @Query("select c from Conversation c order by c.lastMessageAt desc nulls last, c.createdAt desc")
    List<Conversation> everything();
}

interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** The newest messages of a conversation, newest first. */
    @Query("select m from ChatMessage m where m.conversationId = ?1 order by m.createdAt desc, m.id desc")
    List<ChatMessage> newest(UUID conversationId, Limit limit);

    /** The newest messages sent before a moment, newest first (older pages of a long conversation). */
    @Query("select m from ChatMessage m where m.conversationId = ?1 and m.createdAt < ?2 "
            + "order by m.createdAt desc, m.id desc")
    List<ChatMessage> newestBefore(UUID conversationId, Instant before, Limit limit);

    @Query("select m from ChatMessage m where m.conversationId = ?1 order by m.createdAt, m.id")
    List<ChatMessage> allOf(UUID conversationId);

    /** Messages created or changed (read, deleted, erased) after a moment, oldest first. */
    @Query("select m from ChatMessage m where m.conversationId = ?1 and m.updatedAt > ?2 order by m.createdAt, m.id")
    List<ChatMessage> changedSince(UUID conversationId, Instant since);

    /** The other side's messages not yet seen, for marking them read. */
    @Query("select m from ChatMessage m where m.conversationId = ?1 and m.senderSide = ?2 and m.readAt is null")
    List<ChatMessage> unreadFrom(UUID conversationId, Side sender);

    /** Parents' messages a member of staff has not seen, per conversation: {conversationId, count}. */
    @Query("select m.conversationId, count(m) from ChatMessage m, Conversation c where c.id = m.conversationId "
            + "and c.staffUserId = ?1 and m.senderSide = com.akshara.chat.Side.PARENT and m.readAt is null "
            + "and m.deletedAt is null and m.erasedAt is null group by m.conversationId")
    List<Object[]> unreadForStaff(UUID staffUserId);

    /** Staff messages a parent has not seen, per conversation: {conversationId, count}. */
    @Query("select m.conversationId, count(m) from ChatMessage m, Conversation c where c.id = m.conversationId "
            + "and c.parentUserId = ?1 and m.senderSide = com.akshara.chat.Side.STAFF and m.readAt is null "
            + "and m.deletedAt is null and m.erasedAt is null group by m.conversationId")
    List<Object[]> unreadForParent(UUID parentUserId);

    /** When the person sent their messages since a moment, for the hourly limit. */
    @Query("select m.createdAt from ChatMessage m where m.senderUserId = ?1 and m.createdAt > ?2")
    List<Instant> sentSince(UUID senderUserId, Instant since);

    /** The last message of each conversation. */
    @Query("select m from ChatMessage m where m.conversationId in ?1 and m.createdAt = "
            + "(select max(m2.createdAt) from ChatMessage m2 where m2.conversationId = m.conversationId)")
    List<ChatMessage> lastOf(Collection<UUID> conversationIds);
}

interface ChatReportRepository extends JpaRepository<ChatReport, UUID> {

    List<ChatReport> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    List<ChatReport> findByConversationIdIn(Collection<UUID> conversationIds);

    @Query("select r from ChatReport r order by r.createdAt desc")
    List<ChatReport> newestFirst();

    @Query("select r from ChatReport r where r.status = ?1 order by r.createdAt desc")
    List<ChatReport> withStatus(ChatReport.Status status);

    long countByStatus(ChatReport.Status status);

    boolean existsByConversationIdAndReportedByIdAndStatus(UUID conversationId, UUID reportedById,
            ChatReport.Status status);
}

interface PtmEventRepository extends JpaRepository<PtmEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PtmEvent e where e.id = ?1")
    Optional<PtmEvent> lock(UUID id);

    @Query("select e from PtmEvent e order by e.meetingDate desc, e.startsAt desc, e.createdAt desc")
    List<PtmEvent> newestFirst();

    @Query("select e from PtmEvent e where e.id in ?1 order by e.meetingDate, e.startsAt")
    List<PtmEvent> byIds(Collection<UUID> ids);
}

interface PtmSectionRepository extends JpaRepository<PtmSection, UUID> {

    List<PtmSection> findByEventId(UUID eventId);

    List<PtmSection> findByEventIdIn(Collection<UUID> eventIds);

    List<PtmSection> findBySectionIdIn(Collection<UUID> sectionIds);

    List<PtmSection> findByEventIdAndSectionIdIn(UUID eventId, Collection<UUID> sectionIds);
}

interface PtmTeacherRepository extends JpaRepository<PtmTeacher, UUID> {

    List<PtmTeacher> findByEventId(UUID eventId);

    List<PtmTeacher> findByEventIdIn(Collection<UUID> eventIds);

    Optional<PtmTeacher> findByEventIdAndTeacherUserId(UUID eventId, UUID teacherUserId);

    List<PtmTeacher> findByTeacherUserId(UUID teacherUserId);
}

interface PtmSlotRepository extends JpaRepository<PtmSlot, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PtmSlot s where s.id = ?1")
    Optional<PtmSlot> lock(UUID id);

    @Query("select s from PtmSlot s where s.eventId = ?1 order by s.startsAt, s.teacherUserId")
    List<PtmSlot> ofEvent(UUID eventId);

    @Query("select s from PtmSlot s where s.eventId in ?1 order by s.startsAt")
    List<PtmSlot> ofEvents(Collection<UUID> eventIds);

    @Query("select s from PtmSlot s where s.ptmTeacherId = ?1 order by s.startsAt")
    List<PtmSlot> ofTeacher(UUID ptmTeacherId);
}

interface PtmBookingRepository extends JpaRepository<PtmBooking, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PtmBooking b where b.id = ?1")
    Optional<PtmBooking> lock(UUID id);

    @Query("select b from PtmBooking b where b.eventId = ?1 order by b.createdAt")
    List<PtmBooking> ofEvent(UUID eventId);

    @Query("select b from PtmBooking b where b.eventId in ?1 order by b.createdAt")
    List<PtmBooking> ofEvents(Collection<UUID> eventIds);

    @Query("select b from PtmBooking b where b.slotId = ?1 and b.status = com.akshara.chat.PtmBooking.Status.BOOKED")
    Optional<PtmBooking> activeOfSlot(UUID slotId);

    @Query("select b from PtmBooking b where b.eventId = ?1 and b.teacherUserId = ?2 and b.studentId = ?3 "
            + "and b.status = com.akshara.chat.PtmBooking.Status.BOOKED")
    Optional<PtmBooking> activeOf(UUID eventId, UUID teacherUserId, UUID studentId);

    @Query("select count(b) > 0 from PtmBooking b where b.slotId = ?1")
    boolean slotUsed(UUID slotId);

    List<PtmBooking> findByStudentId(UUID studentId);

    List<PtmBooking> findBySlotId(UUID slotId);

    @Query("select b from PtmBooking b where b.eventId = ?1 and b.status = com.akshara.chat.PtmBooking.Status.BOOKED")
    List<PtmBooking> activeOfEvent(UUID eventId);

    @Query("select b from PtmBooking b where b.eventId in ?1 and b.studentId in ?2 order by b.createdAt")
    List<PtmBooking> ofEventsAndStudents(Collection<UUID> eventIds, Collection<UUID> studentIds);
}
