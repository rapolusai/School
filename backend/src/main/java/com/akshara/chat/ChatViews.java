package com.akshara.chat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.akshara.files.StoredFileInfo;

/**
 * Responses of the messages API (docs/api/phase-2-chat.md). None of them carries a phone number or an email address:
 * staff see the parent's name and the child, parents see the teacher's name and what they teach the child.
 */
public final class ChatViews {

    private ChatViews() {
    }

    /** Why a conversation can only be read. */
    public enum ReadOnlyReason {
        CLOSED, ERASED, STUDENT_LEFT, STAFF_LEFT, PARENT_UNLINKED
    }

    public record StudentRef(UUID id, String name, String classLabel) {
    }

    public record Attachment(UUID id, String name, String contentType, int size) {

        static Attachment of(StoredFileInfo f) {
            return new Attachment(f.id(), f.name(), f.contentType(), f.size());
        }
    }

    /**
     * One message as a participant sees it. A deleted message keeps its place with {@code deleted} and no text or
     * attachments; {@code readAt} is when the other side first saw it (the read receipt of one's own messages).
     */
    public record MessageView(UUID id, Side side, boolean mine, String senderName, String body,
            List<Attachment> attachments, boolean deleted, boolean erased, Instant createdAt, Instant readAt,
            Instant deletableUntil, boolean canDelete) {
    }

    /** The last message of a conversation for the list: its start, or what kind of message it was. */
    public record Preview(String text, boolean mine, boolean deleted, int attachments, Instant at) {
    }

    /** The teacher's hours for messages as the parent sees them: open now, or when they answer next. */
    public record Availability(boolean openNow, Instant nextOpenAt, List<DayOfWeek> days, LocalTime from,
            LocalTime to, LocalDate awayUntil, String awayNote) {
    }

    /** A line of the conversation list. {@code with} is the parent (for staff) or the teacher (for parents). */
    public record ConversationRow(UUID id, StudentRef student, String withName, String withDetail, int unread,
            Preview last, Instant lastMessageAt, boolean readOnly, ReadOnlyReason readOnlyReason, boolean away) {
    }

    public record ConversationDetail(UUID id, Side mySide, StudentRef student, String parentName, String staffName,
            String staffDetail, boolean readOnly, ReadOnlyReason readOnlyReason, String closedByName,
            String closeReason, Instant closedAt, Availability staffAvailability, List<MessageView> messages,
            boolean hasOlder, boolean reportedByMe, Instant serverTime) {
    }

    /** What changed in an open conversation since the last look (new, read and deleted messages). */
    public record ConversationChanges(UUID id, boolean readOnly, ReadOnlyReason readOnlyReason,
            List<MessageView> messages, Instant serverTime) {
    }

    public record Unread(int unread, int conversations) {
    }

    /**
     * Someone the caller may start a conversation with about a student: for staff a parent with a sign-in, for a
     * parent a teacher of their child. {@code conversationId} is set when one already exists.
     */
    public record Contact(UUID studentId, String studentName, String classLabel, UUID personId, String personName,
            String detail, UUID conversationId, LocalDate awayUntil) {
    }

    public record ContactSection(UUID id, String label) {
    }

    /**
     * Who the caller may write to. For staff: the sections they may pick parents from, the section shown and its
     * parents; for a parent: the teachers of each child (no sections).
     */
    public record Contacts(List<ContactSection> sections, UUID sectionId, List<Contact> contacts) {
    }

    /** The message just sent, with the notice a parent sees when the teacher is outside their hours or away. */
    public record Sent(MessageView message, Availability staffAvailability) {
    }

    public record Away(boolean away, LocalDate returnsOn, String note) {
    }

    public record Settings(List<DayOfWeek> availableDays, LocalTime availableFrom, LocalTime availableTo,
            boolean notifyNewMessages, boolean ptmRemindersEnabled) {
    }

    // ------------------------------------------------------------------ oversight

    public record OversightRow(UUID id, StudentRef student, String parentName, String staffName, int messageCount,
            Instant lastMessageAt, boolean closed, boolean readOnly, ReadOnlyReason readOnlyReason, int openReports,
            Instant createdAt) {
    }

    public record OversightPage(List<OversightRow> items, int page, int size, long total, long openReports) {
    }

    /** A message as oversight sees it: a deleted message keeps its original text and attachments, marked deleted. */
    public record OversightMessage(UUID id, Side side, String senderName, String body, List<Attachment> attachments,
            boolean deleted, Instant deletedAt, boolean erased, Instant createdAt, Instant readAt) {
    }

    public record ReportView(UUID id, UUID conversationId, StudentRef student, String parentName, String staffName,
            String reportedByName, Side reporterSide, String reason, String status, Instant createdAt,
            String reviewedByName, Instant reviewedAt, String reviewNote) {
    }

    public record OversightDetail(UUID id, StudentRef student, String parentName, String staffName, Side startedBy,
            boolean closed, boolean readOnly, ReadOnlyReason readOnlyReason, String closedByName, String closeReason,
            Instant closedAt, Instant createdAt, List<OversightMessage> messages, List<ReportView> reports) {
    }
}
