package com.akshara.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.files.FileStore;
import com.akshara.privacy.StudentDataErased;

/**
 * Clears what messages and PTM keep about a student whose data is erased (privacy module), inside that transaction:
 * the text and attachments of every message in the student's conversations (the placeholders stay, so the counts and
 * dates still add up), the parent's name on them, report reasons, and the notes of the student's PTM bookings. The
 * conversations become read-only, and notices and reminders still waiting are dropped.
 */
@Component
class ChatErasure {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatReportRepository reports;
    private final PtmBookingRepository bookings;
    private final FileStore files;
    private final ChatNotifier notifier;
    private final PtmReminders reminders;

    ChatErasure(ConversationRepository conversations, ChatMessageRepository messages, ChatReportRepository reports,
            PtmBookingRepository bookings, FileStore files, ChatNotifier notifier, PtmReminders reminders) {
        this.conversations = conversations;
        this.messages = messages;
        this.reports = reports;
        this.bookings = bookings;
        this.files = files;
        this.notifier = notifier;
        this.reminders = reminders;
    }

    @EventListener
    void on(StudentDataErased event) {
        Instant at = event.at() == null ? Instant.now() : event.at();
        List<UUID> withFiles = new ArrayList<>();
        for (Conversation c : conversations.findByStudentId(event.studentId())) {
            for (ChatMessage m : messages.allOf(c.getId())) {
                if (m.getAttachmentCount() > 0) {
                    withFiles.add(m.getId());
                }
                m.erase(at);
            }
            reports.findByConversationIdOrderByCreatedAtDesc(c.getId()).forEach(ChatReport::erase);
            c.erase(at);
            conversations.saveAndFlush(c);
            notifier.silence(c, "Student data erased");
        }
        for (PtmBooking b : bookings.findByStudentId(event.studentId())) {
            b.erase();
            bookings.saveAndFlush(b);
            reminders.unschedule(b, "Student data erased");
        }
        messages.flush();
        // Last: deleting files clears the persistence context, so every change above is written first.
        withFiles.forEach(id -> files.deleteAll(ConversationService.owner(id)));
    }
}
