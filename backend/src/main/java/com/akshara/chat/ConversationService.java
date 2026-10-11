package com.akshara.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatAccess.Me;
import com.akshara.chat.ChatPeople.ChildTeacher;
import com.akshara.chat.ChatPeople.ParentOf;
import com.akshara.chat.ChatPeople.Person;
import com.akshara.chat.ChatPeople.StudentInfo;
import com.akshara.chat.ChatViews.Attachment;
import com.akshara.chat.ChatViews.Availability;
import com.akshara.chat.ChatViews.Contact;
import com.akshara.chat.ChatViews.ContactSection;
import com.akshara.chat.ChatViews.Contacts;
import com.akshara.chat.ChatViews.ConversationChanges;
import com.akshara.chat.ChatViews.ConversationDetail;
import com.akshara.chat.ChatViews.ConversationRow;
import com.akshara.chat.ChatViews.MessageView;
import com.akshara.chat.ChatViews.Preview;
import com.akshara.chat.ChatViews.ReadOnlyReason;
import com.akshara.chat.ChatViews.ReportView;
import com.akshara.chat.ChatViews.Sent;
import com.akshara.chat.ChatViews.StudentRef;
import com.akshara.chat.ChatViews.Unread;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Conversations between parents and staff, as one of the two people in them sees them ({@link Me#side()}): starting
 * one, the list with unread counts, reading (which marks the other side's messages read), sending text and up to three
 * attachments, deleting one's own message within ten minutes, and reporting a conversation to the Principal.
 *
 * <p>A parent reaches only the class teacher and subject teachers of their own children; a member of staff only the
 * parents of students in the sections they lead or teach (or any parent, with chat.oversee). Conversations of other
 * people answer 404, like ids of another school. No phone number or email address is ever in a response.
 */
@Service
@Transactional
class ConversationService {

    static final String MODULE = "chat";
    static final String MESSAGE_FILE = "message";

    /** How far before {@code since} an open chat looks again, for messages committed just after its last look. */
    static final Duration SINCE_OVERLAP = Duration.ofSeconds(30);

    private static final int PREVIEW_LENGTH = 120;

    /** A conversation just started, or the one that already existed. */
    record Started(boolean created, ConversationDetail conversation) {
    }

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatReportRepository reports;
    private final ChatPeople people;
    private final ChatSettingsService settings;
    private final ChatNotifier notifier;
    private final FileStore files;
    private final AuditService audit;

    ConversationService(ConversationRepository conversations, ChatMessageRepository messages,
            ChatReportRepository reports, ChatPeople people, ChatSettingsService settings, ChatNotifier notifier,
            FileStore files, AuditService audit) {
        this.conversations = conversations;
        this.messages = messages;
        this.reports = reports;
        this.people = people;
        this.settings = settings;
        this.notifier = notifier;
        this.files = files;
        this.audit = audit;
    }

    static FileOwner owner(UUID messageId) {
        return new FileOwner(MODULE, MESSAGE_FILE, messageId);
    }

    // ------------------------------------------------------------------ list, unread, contacts

    @Transactional(readOnly = true)
    List<ConversationRow> list(Me me, Instant now) {
        TenantContext.require();
        List<Conversation> mine = (me.side() == Side.STAFF ? conversations.ofStaff(me.id())
                : conversations.ofParent(me.id())).stream()
                // A conversation the other side started but has not written in yet is not shown to this side.
                .filter(c -> c.getMessageCount() > 0 || c.getStartedBy() == me.side())
                .toList();
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> unread = unreadCounts(me);
        Map<UUID, ChatMessage> last = lastMessages(mine.stream().map(Conversation::getId).toList());
        Context ctx = context(mine);
        LocalDate today = now.atZone(ChatRules.INDIA).toLocalDate();
        Map<UUID, StaffAway> away = me.side() == Side.PARENT
                ? settings.awayOf(mine.stream().map(Conversation::getStaffUserId).toList(), today)
                : Map.of();
        Map<UUID, List<ChildTeacher>> teachersByStudent = new HashMap<>();
        List<ConversationRow> rows = new ArrayList<>();
        for (Conversation c : mine) {
            StudentInfo student = ctx.students.get(c.getStudentId());
            String withName;
            String withDetail = null;
            if (me.side() == Side.STAFF) {
                withName = ctx.parentName(c);
            } else {
                withName = name(ctx.people.get(c.getStaffUserId()));
                withDetail = teachersByStudent.computeIfAbsent(c.getStudentId(), people::teachersOf).stream()
                        .filter(t -> t.teacherId().equals(c.getStaffUserId())).findFirst()
                        .map(ChildTeacher::detail).orElse(null);
            }
            ReadOnlyReason reason = ctx.readOnly(c);
            ChatMessage lastMessage = last.get(c.getId());
            rows.add(new ConversationRow(c.getId(), ref(c.getStudentId(), student), withName, withDetail,
                    unread.getOrDefault(c.getId(), 0), preview(lastMessage, me.side()), c.getLastMessageAt(),
                    reason != null, reason, away.containsKey(c.getStaffUserId())));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    Unread unread(Me me) {
        TenantContext.require();
        Map<UUID, Integer> counts = unreadCounts(me);
        return new Unread(counts.values().stream().mapToInt(Integer::intValue).sum(), counts.size());
    }

    /**
     * Who the caller may start a conversation with. A parent: the teachers of each of their children. A member of
     * staff: the parents (with a sign-in) of the students of one section they lead or teach ({@code sectionId}, or the
     * first); with chat.oversee any section.
     */
    @Transactional(readOnly = true)
    Contacts contacts(Me me, boolean oversee, UUID sectionId, Instant now) {
        TenantContext.require();
        Map<List<UUID>, Conversation> existing = new HashMap<>();
        if (me.side() == Side.PARENT) {
            conversations.ofParent(me.id()).forEach(c -> existing.put(key(c.getStudentId(), c.getStaffUserId()), c));
            Map<UUID, StudentInfo> children = people.students(people.childrenOf(me.id()));
            LocalDate today = now.atZone(ChatRules.INDIA).toLocalDate();
            List<Contact> result = new ArrayList<>();
            for (StudentInfo child : children.values()) {
                if (!child.active()) {
                    continue;
                }
                List<ChildTeacher> teachers = people.teachersOf(child.id());
                Map<UUID, StaffAway> away = settings.awayOf(teachers.stream().map(ChildTeacher::teacherId).toList(),
                        today);
                for (ChildTeacher t : teachers) {
                    Conversation c = existing.get(key(child.id(), t.teacherId()));
                    StaffAway gone = away.get(t.teacherId());
                    result.add(new Contact(child.id(), child.name(), child.classLabel(), t.teacherId(), t.name(),
                            t.detail(), c == null ? null : c.getId(), gone == null ? null : gone.getReturnsOn()));
                }
            }
            return new Contacts(List.of(), null, result);
        }
        Map<UUID, SectionInfo> all = people.sectionsById();
        Set<UUID> allowed = oversee ? all.keySet() : people.sectionsOf(me.id());
        List<ContactSection> sections = all.values().stream().filter(s -> allowed.contains(s.id()))
                .sorted(Comparator.comparingInt(SectionInfo::classOrder).thenComparing(SectionInfo::name))
                .map(s -> new ContactSection(s.id(), s.label())).toList();
        UUID chosen = sectionId;
        if (chosen != null && !allowed.contains(chosen)) {
            throw ApiException.badRequest("Choose a class you teach.", "sectionId");
        }
        if (chosen == null) {
            chosen = sections.isEmpty() ? null : sections.get(0).id();
        }
        if (chosen == null) {
            return new Contacts(sections, null, List.of());
        }
        conversations.ofStaff(me.id()).forEach(c -> existing.put(key(c.getStudentId(), c.getParentUserId()), c));
        List<ParentOf> parents = people.parents(List.of(chosen)).stream()
                .filter(p -> !p.parentUserId().equals(me.id())).toList();
        Map<UUID, String> names = people.studentNames(parents.stream().map(ParentOf::studentId).toList());
        Map<UUID, Person> accounts = people.people(parents.stream().map(ParentOf::parentUserId).toList());
        String label = all.get(chosen).label();
        List<Contact> result = new ArrayList<>();
        for (ParentOf p : parents) {
            Person account = accounts.get(p.parentUserId());
            if (account == null || !account.active()) {
                continue;
            }
            Conversation c = existing.get(key(p.studentId(), p.parentUserId()));
            result.add(new Contact(p.studentId(), names.get(p.studentId()), label, p.parentUserId(), p.parentName(),
                    null, c == null ? null : c.getId(), null));
        }
        return new Contacts(sections, chosen, result);
    }

    // ------------------------------------------------------------------ start

    /** A parent starts (or reopens the existing) conversation with a teacher of their child. */
    Started startWithTeacher(Me me, UUID studentId, UUID teacherUserId, Instant now) {
        TenantContext.require();
        if (!people.isParentOf(me.id(), studentId)) {
            throw ApiException.badRequest("Choose one of your children.", "studentId");
        }
        StudentInfo child = people.student(studentId)
                .orElseThrow(() -> ApiException.badRequest("Choose one of your children.", "studentId"));
        if (!child.active()) {
            throw ApiException.badRequest(child.name() + " has left the school.", "studentId");
        }
        if (people.teacherOf(studentId, teacherUserId).isEmpty()) {
            throw ApiException.badRequest("Choose the class teacher or a subject teacher of " + child.name() + ".",
                    "teacherUserId");
        }
        return start(me, studentId, me.id(), teacherUserId, now);
    }

    /** A member of staff starts (or reopens the existing) conversation with a parent of a student they teach. */
    Started startWithParent(Me me, boolean oversee, UUID studentId, UUID parentUserId, Instant now) {
        TenantContext.require();
        StudentInfo student = people.student(studentId)
                .orElseThrow(() -> ApiException.badRequest("Choose a student of this school.", "studentId"));
        if (!student.active()) {
            throw ApiException.badRequest(student.name() + " has left the school.", "studentId");
        }
        if (!oversee && !people.teaches(me.id(), studentId)) {
            throw ApiException.badRequest("You can write to the parents of students you teach.", "studentId");
        }
        Person parent = people.person(parentUserId).orElse(null);
        if (parent == null || !parent.active() || parentUserId.equals(me.id())
                || !people.isParentOf(parentUserId, studentId)) {
            throw ApiException.badRequest("Choose a parent of " + student.name() + " who uses the app.",
                    "parentUserId");
        }
        return start(me, studentId, parentUserId, me.id(), now);
    }

    private Started start(Me me, UUID studentId, UUID parentUserId, UUID staffUserId, Instant now) {
        Optional<Conversation> found = conversations.findByStudentIdAndParentUserIdAndStaffUserId(studentId,
                parentUserId, staffUserId);
        if (found.isPresent()) {
            return new Started(false, detail(me, found.get().getId(), null, now));
        }
        Conversation c;
        try {
            c = conversations.saveAndFlush(new Conversation(studentId, parentUserId, staffUserId, me.side(), now));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("This conversation was just started. Open it from your list.", "studentId");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("studentId", studentId.toString());
        details.put("startedBy", me.side().name());
        audit.record(me.actor(), "chat_conversation.started", "chat_conversation", c.getId(), details);
        return new Started(true, detail(me, c.getId(), null, now));
    }

    // ------------------------------------------------------------------ read

    /**
     * The conversation with its newest messages ({@code before} null) or the page before a moment. Opening it marks
     * the other side's messages read.
     */
    ConversationDetail detail(Me me, UUID id, Instant before, Instant now) {
        Conversation c = visible(me, id);
        List<ChatMessage> page = before == null
                ? messages.newest(id, Limit.of(ChatRules.PAGE_MESSAGES + 1))
                : messages.newestBefore(id, before, Limit.of(ChatRules.PAGE_MESSAGES + 1));
        boolean hasOlder = page.size() > ChatRules.PAGE_MESSAGES;
        List<ChatMessage> shown = new ArrayList<>(page.subList(0, Math.min(page.size(), ChatRules.PAGE_MESSAGES)));
        shown.sort(Comparator.comparing(ChatMessage::getCreatedAt).thenComparing(ChatMessage::getId));
        if (before == null) {
            markRead(c, me.side(), now);
        }
        Context ctx = context(List.of(c));
        ReadOnlyReason reason = ctx.readOnly(c);
        StudentInfo student = ctx.students.get(c.getStudentId());
        String staffDetail = me.side() == Side.PARENT ? people.teacherOf(c.getStudentId(), c.getStaffUserId())
                .map(ChildTeacher::detail).orElse(null) : null;
        Availability availability = settings.availability(c.getStaffUserId(), now);
        boolean reported = reports.existsByConversationIdAndReportedByIdAndStatus(id, me.id(),
                ChatReport.Status.OPEN);
        return new ConversationDetail(c.getId(), me.side(), ref(c.getStudentId(), student),
                ctx.parentName(c), name(ctx.people.get(c.getStaffUserId())), staffDetail,
                reason != null, reason, c.getClosedByName(), c.getCloseReason(), c.getClosedAt(), availability,
                views(shown, me, now), hasOlder, reported, now);
    }

    /**
     * What changed since an open chat last looked ({@code since}, the {@code serverTime} it was given): new messages,
     * read receipts and deletions. New messages from the other side are marked read, as the chat is open.
     */
    ConversationChanges changes(Me me, UUID id, Instant since, Instant now) {
        Conversation c = visible(me, id);
        markRead(c, me.side(), now);
        List<ChatMessage> changed = messages.changedSince(id, since.minus(SINCE_OVERLAP));
        ReadOnlyReason reason = context(List.of(c)).readOnly(c);
        return new ConversationChanges(c.getId(), reason != null, reason, views(changed, me, now), now);
    }

    // ------------------------------------------------------------------ send, delete, report

    /** Sends a message: text (up to 2000 characters), up to three attachments, or both. */
    Sent send(Me me, UUID id, String text, List<IncomingFile> attachments, Instant now) {
        Conversation visible = visible(me, id);
        Conversation c = conversations.lock(visible.getId()).orElseThrow(() -> ApiException.notFound("Conversation"));
        ReadOnlyReason reason = context(List.of(c)).readOnly(c);
        if (reason != null) {
            throw ApiException.conflict(readOnlyMessage(reason), "body");
        }
        String body = text == null || text.isBlank() ? null : text.strip();
        if (body != null && body.length() > ChatRules.MAX_LENGTH) {
            throw ApiException.badRequest("Write at most " + ChatRules.MAX_LENGTH + " characters.", "body");
        }
        if (attachments.size() > ChatRules.MAX_ATTACHMENTS) {
            throw ApiException.badRequest("Attach at most " + ChatRules.MAX_ATTACHMENTS + " files.", "files");
        }
        if (body == null && attachments.isEmpty()) {
            throw ApiException.badRequest("Write a message or attach a file.", "body");
        }
        Optional<Duration> wait = ChatRules.retryAfter(messages.sentSince(me.id(), now.minus(ChatRules.RATE_WINDOW)),
                now);
        if (wait.isPresent()) {
            String detail = "You have sent " + ChatRules.MAX_PER_HOUR + " messages in the last hour. Wait "
                    + ChatRules.minutesToWait(wait.get()) + " minutes and send again.";
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages", detail, Map.of("body", detail));
        }
        String senderName = people.person(me.id()).map(Person::name).orElse(me.name());
        ChatMessage m = messages.saveAndFlush(new ChatMessage(c.getId(), me.id(), me.side(), senderName, body,
                attachments.size(), now));
        for (IncomingFile file : attachments) {
            files.put(owner(m.getId()), file, me.id());
        }
        c.messageAdded(now);
        conversations.saveAndFlush(c);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conversationId", c.getId().toString());
        details.put("side", me.side().name());
        details.put("attachments", attachments.size());
        audit.record(me.actor(), "chat_message.sent", "chat_message", m.getId(), details);
        StudentInfo student = people.student(c.getStudentId()).orElse(null);
        notifier.newMessage(c, m, student == null ? null : student.name(), now);
        return new Sent(views(List.of(m), me, now).get(0), settings.availability(c.getStaffUserId(), now));
    }

    /**
     * Deletes the caller's own message for both sides, within ten minutes of sending it: the placeholder stays and the
     * original is kept for oversight.
     */
    MessageView delete(Me me, UUID id, UUID messageId, Instant now) {
        Conversation c = visible(me, id);
        ChatMessage m = messages.findById(messageId).filter(x -> x.getConversationId().equals(c.getId()))
                .orElseThrow(() -> ApiException.notFound("Message"));
        if (!me.id().equals(m.getSenderUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed", "You can delete only your own messages.");
        }
        if (m.isDeleted() || m.isErased()) {
            throw ApiException.conflict("This message is already deleted.", "messageId");
        }
        if (!m.deletableAt(now)) {
            throw ApiException.conflict("A message can be deleted only within "
                    + ChatRules.DELETE_WINDOW.toMinutes() + " minutes of sending it.", "messageId");
        }
        ReadOnlyReason reason = context(List.of(c)).readOnly(c);
        if (reason != null) {
            throw ApiException.conflict(readOnlyMessage(reason), "messageId");
        }
        m.delete(now);
        messages.saveAndFlush(m);
        notifier.withdrawn(m);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conversationId", c.getId().toString());
        details.put("sentAt", m.getCreatedAt().toString());
        audit.record(me.actor(), "chat_message.deleted", "chat_message", m.getId(), details);
        return views(List.of(m), me, now).get(0);
    }

    /** Flags the conversation for the Principal (chat.oversee). One open report per person and conversation. */
    ReportView report(Me me, UUID id, String reason, Instant now) {
        Conversation c = visible(me, id);
        if (reports.existsByConversationIdAndReportedByIdAndStatus(c.getId(), me.id(), ChatReport.Status.OPEN)) {
            throw ApiException.conflict("You have already reported this conversation. The Principal will look "
                    + "at it.", "reason");
        }
        String name = people.person(me.id()).map(Person::name).orElse(me.name());
        ChatReport r = reports.saveAndFlush(new ChatReport(c.getId(), new Actor(me.id(), name), me.side(),
                reason.strip(), now));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reportId", r.getId().toString());
        details.put("side", me.side().name());
        audit.record(me.actor(), "chat_conversation.reported", "chat_conversation", c.getId(), details);
        Context ctx = context(List.of(c));
        return reportView(r, c, ctx);
    }

    // ------------------------------------------------------------------ shared with oversight

    /** The people and students of some conversations, and why each is read-only (if it is). */
    final class Context {

        final Map<UUID, StudentInfo> students;
        final Map<UUID, Person> people;
        private final Map<UUID, Set<UUID>> childrenByParent = new HashMap<>();

        Context(Map<UUID, StudentInfo> students, Map<UUID, Person> people) {
            this.students = students;
            this.people = people;
        }

        /** The parent's name, or "Parent" once the student's data is erased. */
        String parentName(Conversation c) {
            return c.isErased() ? ChatMessage.ERASED_PARENT : name(people.get(c.getParentUserId()));
        }

        ReadOnlyReason readOnly(Conversation c) {
            if (c.isErased()) {
                return ReadOnlyReason.ERASED;
            }
            if (c.isClosed()) {
                return ReadOnlyReason.CLOSED;
            }
            StudentInfo student = students.get(c.getStudentId());
            if (student == null || !student.active()) {
                return ReadOnlyReason.STUDENT_LEFT;
            }
            Person staff = people.get(c.getStaffUserId());
            if (staff == null || !staff.canChat()) {
                return ReadOnlyReason.STAFF_LEFT;
            }
            Person parent = people.get(c.getParentUserId());
            if (parent == null || !parent.active() || !childrenByParent
                    .computeIfAbsent(c.getParentUserId(), ConversationService.this.people::childrenOf)
                    .contains(c.getStudentId())) {
                return ReadOnlyReason.PARENT_UNLINKED;
            }
            return null;
        }
    }

    Context context(Collection<Conversation> list) {
        Set<UUID> studentIds = new LinkedHashSet<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        for (Conversation c : list) {
            studentIds.add(c.getStudentId());
            userIds.add(c.getParentUserId());
            userIds.add(c.getStaffUserId());
        }
        return new Context(people.students(studentIds), people.people(userIds));
    }

    ReportView reportView(ChatReport r, Conversation c, Context ctx) {
        return new ReportView(r.getId(), c.getId(), ref(c.getStudentId(), ctx.students.get(c.getStudentId())),
                ctx.parentName(c), name(ctx.people.get(c.getStaffUserId())),
                r.getReportedByName(), r.getReporterSide(), r.getReason(), r.getStatus().name(), r.getCreatedAt(),
                r.getReviewedByName(), r.getReviewedAt(), r.getReviewNote());
    }

    static StudentRef ref(UUID id, StudentInfo s) {
        return s == null ? new StudentRef(id, null, null) : new StudentRef(id, s.name(), s.classLabel());
    }

    static String name(Person p) {
        return p == null ? null : p.name();
    }

    static String readOnlyMessage(ReadOnlyReason reason) {
        return switch (reason) {
            case CLOSED -> "The school has closed this conversation. You can still read it.";
            case ERASED -> "This conversation was erased with the student's data.";
            case STUDENT_LEFT -> "The student has left the school. You can still read this conversation.";
            case STAFF_LEFT -> "This teacher no longer uses messages at the school. You can still read this "
                    + "conversation.";
            case PARENT_UNLINKED -> "This parent is no longer linked to the student. You can still read this "
                    + "conversation.";
        };
    }

    Map<UUID, List<Attachment>> attachments(Collection<ChatMessage> list, boolean includeDeleted) {
        List<UUID> ids = list.stream()
                .filter(m -> m.getAttachmentCount() > 0 && !m.isErased() && (includeDeleted || !m.isDeleted()))
                .map(ChatMessage::getId).toList();
        Map<UUID, List<Attachment>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        files.list(MODULE, MESSAGE_FILE, ids).forEach((messageId, stored) -> result.put(messageId,
                stored.stream().map(Attachment::of).toList()));
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private Conversation visible(Me me, UUID id) {
        TenantContext.require();
        return conversations.findById(id).filter(c -> me.id().equals(c.participant(me.side())))
                .orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    private void markRead(Conversation c, Side reader, Instant now) {
        boolean changed = false;
        for (ChatMessage m : messages.unreadFrom(c.getId(), reader.other())) {
            changed |= m.markRead(now);
        }
        if (changed) {
            messages.flush();
            notifier.seen(c, reader);
        }
    }

    private List<MessageView> views(List<ChatMessage> list, Me me, Instant now) {
        Map<UUID, List<Attachment>> attached = attachments(list, false);
        List<MessageView> result = new ArrayList<>();
        for (ChatMessage m : list) {
            boolean mine = me.id().equals(m.getSenderUserId()) && m.getSenderSide() == me.side();
            boolean hidden = m.isDeleted() || m.isErased();
            result.add(new MessageView(m.getId(), m.getSenderSide(), mine, m.getSenderName(),
                    hidden ? null : m.getBody(), hidden ? List.of() : attached.getOrDefault(m.getId(), List.of()),
                    m.isDeleted(), m.isErased(), m.getCreatedAt(), m.getReadAt(), m.deletableUntil(),
                    mine && m.deletableAt(now)));
        }
        return result;
    }

    private Map<UUID, Integer> unreadCounts(Me me) {
        List<Object[]> rows = me.side() == Side.STAFF ? messages.unreadForStaff(me.id())
                : messages.unreadForParent(me.id());
        Map<UUID, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return result;
    }

    private Map<UUID, ChatMessage> lastMessages(List<UUID> ids) {
        Map<UUID, ChatMessage> result = new HashMap<>();
        for (ChatMessage m : messages.lastOf(ids)) {
            ChatMessage seen = result.get(m.getConversationId());
            if (seen == null || seen.getId().compareTo(m.getId()) < 0) {
                result.put(m.getConversationId(), m);
            }
        }
        return result;
    }

    private static Preview preview(ChatMessage m, Side me) {
        if (m == null) {
            return null;
        }
        boolean hidden = m.isDeleted() || m.isErased();
        String text = hidden || m.getBody() == null ? null
                : m.getBody().length() <= PREVIEW_LENGTH ? m.getBody()
                : m.getBody().substring(0, PREVIEW_LENGTH - 1) + "…";
        return new Preview(text, m.getSenderSide() == me, m.isDeleted(), hidden ? 0 : m.getAttachmentCount(),
                m.getCreatedAt());
    }

    private static List<UUID> key(UUID studentId, UUID personId) {
        return List.of(studentId, personId);
    }
}
