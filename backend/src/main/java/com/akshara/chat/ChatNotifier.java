package com.akshara.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.chat.ChatPeople.Person;
import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;

/**
 * Tells the other side of a conversation that a new message is waiting, through the notifications outbox (simulated:
 * nothing leaves the system), never with the message itself. One notice per run of unread messages. A notice to a
 * member of staff waits until their next working hour (the school's days and hours, holidays, their away note); a
 * notice to a parent goes on the school's alert channel, held through the school's quiet hours by the dispatcher.
 * A notice still waiting is dropped when the message is read or deleted first. The unread count in the app does not
 * depend on any of this.
 */
@Component
class ChatNotifier {

    /** The {@link Related} type of notices to staff and to parents, per conversation. */
    static final String TO_STAFF = "chat_to_staff";
    static final String TO_PARENT = "chat_to_parent";

    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final ChatSettingsService settings;
    private final ChatPeople people;
    private final ChatMessageRepository messages;

    ChatNotifier(NotificationQueue queue, NotificationSettingsService messageSettings, TenantDirectory tenants,
            ChatSettingsService settings, ChatPeople people, ChatMessageRepository messages) {
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.settings = settings;
        this.people = people;
        this.messages = messages;
    }

    static String key(UUID messageId) {
        return "chat:" + messageId;
    }

    /** Queues a notice about the message just sent, unless one about an earlier unread message is out already. */
    void newMessage(Conversation c, ChatMessage m, String studentName, Instant now) {
        if (!settings.notifyNewMessages()) {
            return;
        }
        boolean earlierUnread = messages.unreadFrom(c.getId(), m.getSenderSide()).stream()
                .anyMatch(x -> !x.getId().equals(m.getId()) && !x.isDeleted());
        if (earlierUnread) {
            return;
        }
        MessageSettings school = messageSettings.current();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("student", studentName == null ? "" : studentName);
        params.put("school", schoolName());
        if (m.getSenderSide() == Side.PARENT) {
            Person staff = people.person(c.getStaffUserId()).orElse(null);
            if (staff == null || !staff.canChat() || staff.email() == null || !staff.email().contains("@")) {
                return;
            }
            params.put("teacher", staff.name());
            params.put("parent", m.getSenderName());
            Recipient to = new Recipient(Channel.EMAIL, staff.email(), staff.name(), null, null, key(m.getId()));
            queue.enqueue(MessageTemplates.CHAT_TO_STAFF, school.alertLanguage(), params,
                    new Related(TO_STAFF, c.getId(), studentName), List.of(to),
                    settings.nextAllowed(c.getStaffUserId(), now));
        } else {
            Optional<String> phone = people.parentPhone(c.getParentUserId(), c.getStudentId())
                    .map(String::trim).filter(p -> p.matches(PHONE));
            Person parent = people.person(c.getParentUserId()).orElse(null);
            if (phone.isEmpty() || parent == null || !parent.active()) {
                return;
            }
            params.put("teacher", m.getSenderName());
            Recipient to = Recipient.phone(school.absenceAlertChannel(), phone.get(), parent.name(),
                    Map.of("guardian", parent.name()), key(m.getId()));
            queue.enqueue(MessageTemplates.CHAT_TO_PARENT, school.alertLanguage(), params,
                    new Related(TO_PARENT, c.getId(), studentName), List.of(to), now);
        }
    }

    /** The reader saw the conversation: notices to them still waiting are no longer needed. */
    void seen(Conversation c, Side reader) {
        queue.skipRelated(reader == Side.STAFF ? TO_STAFF : TO_PARENT, c.getId(), "Read in the app");
    }

    /** The message was deleted: a notice about it still waiting is dropped. */
    void withdrawn(ChatMessage m) {
        queue.skip(key(m.getId()), "Message deleted");
    }

    /** Nothing more is said in the conversation (closed or erased): every notice still waiting is dropped. */
    void silence(Conversation c, String reason) {
        queue.skipRelated(TO_STAFF, c.getId(), reason);
        queue.skipRelated(TO_PARENT, c.getId(), reason);
    }

    private String schoolName() {
        return tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
    }
}
